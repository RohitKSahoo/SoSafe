# Backend Schema (SoSafe)

*(Adapted to your Firebase-based, real-time, safety-critical architecture)* 

---

## 1. Architecture Overview

### System Architecture

* **Pattern**: Event-driven, real-time (Firebase-based)
* **Authentication**: Code-based identity (no JWT for MVP)
* **Data Flow**:

  ```
  Client (Sender App) → Firebase (Firestore + Storage + FCM) → Client (Receiver App)
  ```
* **Caching Strategy**:

  * Firebase SDK local cache
  * No Redis (MVP simplification)

---

## 2. Database Schema

### Database

* **Primary**: Firestore (NoSQL)
* **Storage**: Firebase Storage (audio chunks)
* **Naming Convention**: camelCase (Firestore standard)
* **Timestamps**: All documents include `createdAt`, `updatedAt`

---

## 3. Collections & Relationships

---

### Collection: users

**Purpose**: Store user identity and device-level data

| Field     | Type          | Constraints      | Description              |
| --------- | ------------- | ---------------- | ------------------------ |
| id        | string        | PRIMARY KEY      | Unique user ID           |
| userCode  | string        | UNIQUE, NOT NULL | 6–8 digit shareable code |
| deviceId  | string        | NOT NULL         | Device identifier        |
| contacts  | array<string> | DEFAULT []       | Linked user IDs          |
| createdAt | timestamp     | DEFAULT now      | Creation time            |
| updatedAt | timestamp     | DEFAULT now      | Last update              |

**Indexes**:

* userCode (unique)

---

### Collection: sos_sessions

**Purpose**: Track active emergency sessions

| Field        | Type      | Constraints             | Description           |
| ------------ | --------- | ----------------------- | --------------------- |
| id           | string    | PRIMARY KEY             | Session ID            |
| userId       | string    | FOREIGN KEY → users.id  | Owner of session      |
| status       | string    | ENUM(‘active’, ‘ended’) | Session state         |
| startTime    | timestamp | NOT NULL                | Start time            |
| endTime      | timestamp | NULL                    | End time              |
| lastLocation | geopoint  | NULL                    | Latest known location |
| batteryLevel | number    | NULL                    | Device battery        |
| createdAt    | timestamp | DEFAULT now             | Creation              |
| updatedAt    | timestamp | DEFAULT now             | Update                |

**Indexes**:

* userId
* status

---

### Collection: location_updates

**Purpose**: Store real-time location updates

| Field     | Type      | Constraints                   | Description |
| --------- | --------- | ----------------------------- | ----------- |
| id        | string    | PRIMARY KEY                   | Unique ID   |
| sessionId | string    | FOREIGN KEY → sos_sessions.id |             |
| location  | geopoint  | NOT NULL                      |             |
| accuracy  | number    | NULL                          |             |
| timestamp | timestamp | NOT NULL                      |             |
| createdAt | timestamp | DEFAULT now                   |             |

**Indexes**:

* sessionId
* timestamp (descending)

---

### Storage: audio_chunks

**Purpose**: Store uploaded audio files

**Structure**:

```
/audio_chunks/{sessionId}/{chunkId}.aac
```

**Metadata (Firestore reference optional)**:

| Field     | Type      | Description        |
| --------- | --------- | ------------------ |
| sessionId | string    | Associated session |
| fileUrl   | string    | Storage URL        |
| duration  | number    | Chunk length       |
| timestamp | timestamp | Upload time        |

---

### Collection: alerts

**Purpose**: Track alert notifications

| Field      | Type      | Constraints                         | Description |
| ---------- | --------- | ----------------------------------- | ----------- |
| id         | string    | PRIMARY KEY                         |             |
| sessionId  | string    | FOREIGN KEY                         |             |
| senderId   | string    | FOREIGN KEY                         |             |
| receiverId | string    | FOREIGN KEY                         |             |
| status     | string    | ENUM(‘sent’, ‘delivered’, ‘failed’) |             |
| createdAt  | timestamp | DEFAULT now                         |             |

**Indexes**:

* receiverId
* sessionId

---

## Relationships Summary

* users ↔ users (contacts: many-to-many via array)
* users → sos_sessions (one-to-many)
* sos_sessions → location_updates (one-to-many)
* sos_sessions → audio_chunks (one-to-many)
* sos_sessions → alerts (one-to-many)

---

## 4. API Endpoints (Logical / Firebase Functions)

*(Note: Using Firebase SDK instead of traditional REST, but structured similarly)*

---

### POST /sos/start

**Purpose**: Start emergency session

**Request**:

```json
{
  "userId": "string"
}
```

**Response**:

```json
{
  "sessionId": "string",
  "status": "active"
}
```

**Side Effects**:

* Creates sos_session
* Sends FCM alerts

---

### POST /sos/location

**Purpose**: Send location update

**Request**:

```json
{
  "sessionId": "string",
  "lat": 0,
  "lng": 0,
  "accuracy": 10
}
```

**Side Effects**:

* Writes to location_updates
* Updates sos_sessions.lastLocation

---

### POST /sos/audio

**Purpose**: Upload audio chunk

**Request**:

* Binary upload (AAC file)

**Side Effects**:

* Stores in Firebase Storage
* Optional metadata entry

---

### POST /contacts/add

**Purpose**: Link users

**Request**:

```json
{
  "userCode": "123456"
}
```

**Response**:

```json
{
  "success": true
}
```

---

## 5. Authentication & Authorization

### Strategy

* Code-based identity (MVP)
* No password / JWT

### Authorization Rules

* Only linked users can access session data
* Firestore security rules enforce access

---

## 6. Data Validation Rules

### User Code

* Length: 6–8 digits
* Must be unique

---

### Location

* Latitude: -90 to 90
* Longitude: -180 to 180

---

### Audio

* Format: AAC
* Max chunk duration: 5 seconds

---

## 7. Error Handling

### Error Format

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Description"
  }
}
```

### Error Codes

* VALIDATION_ERROR → 400
* UNAUTHORIZED → 401
* NOT_FOUND → 404
* SERVER_ERROR → 500

---

## 8. Caching Strategy

### Layers

* Firebase local cache
* Device-level caching

---

### Cache Use Cases

* Last known location
* Active session state

---

### Invalidation

* On session end → clear cache
* On new update → overwrite

---

## 9. Rate Limiting

### Limits

* SOS trigger: unrestricted (priority)
* Location updates: ~1 per 3–5 sec
* Audio uploads: controlled by chunk duration

---

### Implementation

* Client-side throttling
* Firebase quota protection

---

## 10. Database Migrations

### Strategy

* Firestore schema-less → no migrations
* Versioning handled in app logic

---

## 11. Backup & Recovery

### Strategy

* Firebase automatic backups
* Export to GCP periodically

---

### Recovery

* Restore Firestore snapshot
* Re-link storage references

---

## 12. API Versioning

* **Current Version**: v1
* **Strategy**:

  * Version via Firebase functions path
  * Backward compatibility maintained

---

## Final Architecture Insight

You’ve deliberately chosen:

* **Schema-light backend**
* **Event-driven data flow**
* **Client-heavy logic**

This is correct for:

* MVP speed
* Real-time needs
* Low infra overhead


