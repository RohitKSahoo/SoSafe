# 🚀 SoSafe System Architecture v3 (Refined from Audit)

## 🎯 Objective

Transform the current system into a **deterministic, low-latency, real-time streaming architecture** while:

* Preserving working components (Cloudinary, Service)
* Eliminating redundant layers (`alerts`, subcollection-based location)
* Fixing ordering, latency, and listener reliability

---

# 🔴 1. CORE ARCHITECTURAL SHIFT

### ❌ OLD MODEL (CURRENT)

* alerts → discover session
* subcollections → query latest
* timestamp-based ordering
* 5s batching → high latency

---

### ✅ NEW MODEL (TARGET)

* sessions = **single source of truth**
* direct document listeners (no query dependency)
* sequence-based streaming (not timestamp)
* reduced chunk intervals for near real-time

---

# 🧠 2. IDENTITY MODEL (UNCHANGED BUT FORMALIZED)

```id="f9j3ks"
users/{userId}
```

```json id="d92k31"
{
  "userId": "string",
  "contacts": ["pairedUserId"],
  "createdAt": number
}
```

### ✅ Rules:

* userId remains your 8-char code
* pairing remains bidirectional (already implemented)
* RoleManager remains source of truth in app

---

# 🔥 3. SESSION MODEL (PRIMARY ENTITY)

```id="gk82md"
sessions/{sessionId}
```

```json id="p4dk20"
{
  "sessionId": "string",
  "senderId": "string",
  "guardianId": "string",
  "status": "ACTIVE | ENDED",
  "startedAt": timestamp,
  "lastLocation": GeoPoint,
  "lastUpdatedAt": timestamp
}
```

---

### ✅ CRITICAL RULES:

* MUST be created BEFORE any streaming
* Acts as:

  * session registry
  * location stream
  * discovery mechanism

---

# ❌ 4. REMOVE `alerts` COLLECTION (MANDATORY)

### Reason:

* Redundant with session
* Adds latency + failure point
* Already duplicating receiverId logic

---

### ✅ REPLACEMENT (Guardian Discovery)

```kotlin id="s4m9zc"
sessions
.whereEqualTo("guardianId", myUserId)
.whereEqualTo("status", "ACTIVE")
```

---

# 📍 5. LOCATION STREAM (FLATTENED — ZERO QUERY)

## ❌ REMOVE:

```id="a3s8zm"
sos_sessions/{sessionId}/location_updates
```

---

## ✅ NEW APPROACH:

### Sender writes:

```kotlin id="d9x21p"
sessionRef.update(
    mapOf(
        "lastLocation" to GeoPoint(lat, lng),
        "lastUpdatedAt" to FieldValue.serverTimestamp()
    )
)
```

---

### Guardian listens:

```kotlin id="z7k2qp"
sessionRef.addSnapshotListener { snapshot, _ ->
    val location = snapshot?.getGeoPoint("lastLocation")
    updateMap(location)
}
```

---

### ⚡ Performance:

* Update frequency: **1–2 seconds**
* No query → no index → no delay

---

# 🎧 6. AUDIO STREAM (SEQUENCE-BASED)

```id="l0a92x"
sessions/{sessionId}/audio_chunks/{chunkId}
```

```json id="o3d91k"
{
  "fileUrl": "string",
  "sequence": number,
  "duration": number,
  "createdAt": timestamp
}
```

---

## 🔴 CRITICAL CHANGE

### ❌ REMOVE:

```id="j9d0xk"
timestamp-based ordering
```

---

### ✅ USE:

```id="c8v12q"
sequence (0,1,2,3...)
```

---

## Sender Logic:

```kotlin id="p9v2m1"
sequenceCounter++
uploadChunk()
store(sequence = sequenceCounter)
```

---

## Guardian Query:

```kotlin id="m2x8kp"
.orderBy("sequence", Query.Direction.ASCENDING)
.limit(20)
```

---

## Playback Rule:

* Maintain queue
* Never replay same sequence
* Always play next sequence

---

# ⚡ 7. AUDIO LATENCY OPTIMIZATION

### CURRENT:

* 5s chunk + upload + listener = ~7–8s latency

---

### ✅ NEW:

| Parameter       | Value       |
| --------------- | ----------- |
| Chunk size      | 2–3 seconds |
| Upload          | async       |
| Playback buffer | minimal     |

---

### Result:

* ~3–4 seconds latency
* Feels real-time

---

# 🔁 8. SERVICE ARCHITECTURE (KEEP, BUT STABILIZE)

### Keep:

* ForegroundService ✅
* retryFailedUploads() ✅

---

### Ensure:

* isEmergencyActive persists (already done)
* service NEVER depends on UI state

---

# ⚙️ 9. LISTENER STANDARDIZATION

ALL listeners MUST:

```kotlin id="y7x2mz"
if (e != null) {
    Log.e("SOS_AUDIT", "LISTENER_ERROR", e)
    return
}
```

---

# 🔐 10. STATE MODEL

| State            | Location             |
| ---------------- | -------------------- |
| Emergency Active | SharedPreferences    |
| Session Active   | Firestore (`status`) |

---

# 🧪 11. DEBUG CONTRACT

MANDATORY logs:

* SESSION_CREATED
* LOCATION_WRITE
* AUDIO_WRITE
* SNAPSHOT_RECEIVED
* LISTENER_ERROR

---

# 🚀 12. DATA FLOW (FINAL)

## Sender:

1. Create session document
2. Start ForegroundService
3. Update location every 1–2 sec
4. Record audio (2–3 sec chunks)
5. Upload → store with sequence

---

## Guardian:

1. Query active session
2. Attach session listener (location)
3. Attach audio_chunks listener
4. Stream playback sequentially

---

# ⚠️ 13. NON-NEGOTIABLE RULES

* ❌ No alerts collection
* ❌ No location subcollections
* ❌ No timestamp ordering for audio
* ❌ No limitToLast
* ❌ No mixed role logic

---

# 🎯 14. EXPECTED OUTCOME

| Feature     | Result         |
| ----------- | -------------- |
| Location    | ~1–2 sec delay |
| Audio       | ~3–4 sec delay |
| Reliability | Deterministic  |
| Debugging   | Observable     |

---

# 🧠 FINAL INSIGHT

This system is now:

> **Event-driven streaming architecture (not query-driven CRUD)**

If implemented correctly:

* No race conditions
* No silent failures
* No inconsistent playback
