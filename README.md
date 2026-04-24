# Quiz Leaderboard System — Bajaj Finserv Health

<div>
<p align="center">
  <img src="https://img.shields.io/badge/Java-23-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 23">
  <img src="https://img.shields.io/badge/Zero_Dependencies-✓-00C853?style=for-the-badge" alt="Zero Dependencies">
  <img src="https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge" alt="Build Passing">
  <img src="https://img.shields.io/badge/Bajaj_Finserv-Internship-0066CC?style=for-the-badge" alt="Bajaj Finserv">
</p>
</div>

<p align="center">
  A backend system that consumes quiz API responses from a distributed validator, handles <b>duplicate event delivery</b>, builds an accurate leaderboard, and submits the result — all in a single-file, zero-dependency Java application.
</p>

---

## 📋 Table of Contents

- [Problem Statement](#-problem-statement)
- [Architecture & Flow](#-architecture--flow)
- [Deduplication Strategy](#-deduplication-strategy)
- [API Reference](#-api-reference)
- [Getting Started](#-getting-started)
- [Sample Execution](#-sample-execution)
- [Project Structure](#-project-structure)
- [Tech Stack](#-tech-stack)

---

## 🧩 Problem Statement

In distributed systems, API responses may be **delivered more than once**. This assignment simulates a real-world quiz show where participants receive scores across multiple rounds. The validator API serves this data across **10 sequential polls** — but the same event data can appear in multiple polls.

### Objective

> Compute the **total score of all users combined** and generate a **correct leaderboard**, handling duplicate API response data correctly.

### Requirements Checklist

| # | Requirement | Status |
|---|-------------|--------|
| 1 | Execute exactly 10 polls (`poll=0` to `poll=9`) | ✅ |
| 2 | Maintain 5-second delay between each poll | ✅ |
| 3 | Handle duplicate API response data correctly | ✅ |
| 4 | Aggregate scores per participant | ✅ |
| 5 | Generate leaderboard sorted by `totalScore` | ✅ |
| 6 | Compute correct total score across all users | ✅ |
| 7 | Submit leaderboard exactly once | ✅ |

---

## 🏗 Architecture & Flow

The system follows a **linear pipeline architecture** with distinct processing stages:

```
┌─────────────┐     ┌──────────────┐     ┌───────────────┐     ┌──────────────┐     ┌────────────┐
│  API Poller  │────▶│  Collector   │────▶│ Deduplicator  │────▶│  Aggregator  │────▶│  Submitter │
│  (10 polls)  │     │ (raw events) │     │ (unique keys) │     │ (per-user)   │     │  (POST)    │
└─────────────┘     └──────────────┘     └───────────────┘     └──────────────┘     └────────────┘
      │                    │                     │                     │                    │
   5s delay          List<Event>           HashSet<Key>         Map<Name,Score>       JSON payload
  between polls       15 events             9 unique              3 users             1 submission
```

### Step-by-Step Breakdown

**Step 1 — Polling**
```java
for (int poll = 0; poll < 10; poll++) {
    String response = httpGet(BASE_URL + "/quiz/messages?regNo=" + REG_NO + "&poll=" + poll);
    // ... collect events
    Thread.sleep(5000); // mandatory 5-second delay
}
```
The application makes **10 sequential HTTP GET requests** to the validator API, one for each poll index (`0–9`). A mandatory 5-second delay is enforced between requests to comply with rate-limiting requirements.

**Step 2 — Collection**
All events from every poll response are accumulated into a single list. At this stage, duplicates are still present.

**Step 3 — Deduplication**
Using a `HashSet` with composite keys (`roundId|participant`), the system filters out duplicate events. See the [Deduplication Strategy](#-deduplication-strategy) section for details.

**Step 4 — Aggregation**
Scores are aggregated per participant using a `Map<String, Integer>` with `merge()`:
```java
scoreMap.merge(participant, score, Integer::sum);
```

**Step 5 — Sorting & Submission**
The leaderboard is sorted in **descending order** by total score and submitted via a single `POST` request.

---

## 🔁 Deduplication Strategy

### The Problem

In distributed systems, **at-least-once delivery** is common — the same message can arrive multiple times. If duplicates are processed, the final score will be inflated:

```
❌ Incorrect (without dedup):              ✅ Correct (with dedup):

Poll 0 → R1, Alice, +120  ──▶ counted     Poll 0 → R1, Alice, +120  ──▶ counted
Poll 2 → R1, Alice, +120  ──▶ counted     Poll 2 → R1, Alice, +120  ──▶ SKIPPED
Poll 4 → R1, Alice, +120  ──▶ counted     Poll 4 → R1, Alice, +120  ──▶ SKIPPED
                                           
Total = 360  ✗ WRONG                      Total = 120  ✓ CORRECT
```

### The Solution

Each event is uniquely identified by the combination of **`roundId`** and **`participant`**. This composite key is used as a deduplication fingerprint:

```java
Set<String> seen = new HashSet<>();

for (Map<String, Object> event : allEvents) {
    String key = event.get("roundId") + "|" + event.get("participant");
    
    if (seen.add(key)) {          // returns true only if key is NEW
        uniqueEvents.add(event);  // first occurrence → keep
    }
    // duplicate → automatically ignored
}
```

### Why This Works

| Property | Guarantee |
|----------|-----------|
| **Uniqueness** | `roundId + participant` uniquely identifies a scoring event |
| **Idempotency** | Processing the same event multiple times yields the same result as processing it once |
| **O(1) Lookup** | `HashSet.add()` provides constant-time duplicate detection |
| **Order Preserving** | First occurrence is always kept, duplicates are discarded |

### Dedup Results

```
Raw events collected:    15
Unique after dedup:       9
Duplicates removed:       6
```

---

## 📡 API Reference

**Base URL:** `https://devapigw.vidalhealthtpa.com/srm-quiz-task`

### `GET /quiz/messages`

Fetches quiz events for a given poll index.

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `regNo` | `string` | ✅ | Student registration number |
| `poll` | `int` | ✅ | Poll index (`0–9`) |

**Response:**
```json
{
  "regNo": "RA2311003011950",
  "pollIndex": 0,
  "totalPolls": 11,
  "events": [
    { "roundId": "R1", "participant": "Alice", "score": 120 },
    { "roundId": "R1", "participant": "Bob", "score": 95 }
  ],
  "meta": {
    "hint": "Rounds and scores may repeat across polls. Handle them correctly.",
    "totalPollsRequired": 10
  }
}
```

---

### `POST /quiz/submit`

Submits the final leaderboard.

**Request Body:**
```json
{
  "regNo": "RA2311003011950",
  "leaderboard": [
    { "participant": "Bob", "totalScore": 295 },
    { "participant": "Alice", "totalScore": 280 },
    { "participant": "Charlie", "totalScore": 260 }
  ]
}
```

**Response:**
```json
{
  "regNo": "RA2311003011950",
  "totalPollsMade": 10,
  "submittedTotal": 835,
  "attemptCount": 1
}
```

---

## 🚀 Getting Started

### Prerequisites

- **Java JDK 11+** (tested with OpenJDK 23)

> **Note:** This project uses **zero external dependencies** — only JDK built-in classes (`java.net.HttpURLConnection`, `java.util.*`). No Maven, Gradle, or third-party libraries required.

### Clone & Run

```bash
# Clone the repository
git clone https://github.com/your-username/bajaj-quiz-leaderboard.git
cd bajaj-quiz-leaderboard

# Compile
javac QuizLeaderboard.java

# Run
java QuizLeaderboard
```

### Configuration

To use your own registration number, update the constant in `QuizLeaderboard.java`:

```java
private static final String REG_NO = "RA2311003011950";  // ← your reg number
```

---

## 🖥 Sample Execution

```
═══════════════════════════════════════════════════════════
  Quiz Leaderboard System (Java)
  Registration No: RA2311003011950
═══════════════════════════════════════════════════════════

[Poll 0] Fetching...
[Poll 0] Received 2 events (pollIndex: 0)
  ⏳ Waiting 5s before next poll...

[Poll 1] Fetching...
[Poll 1] Received 1 events (pollIndex: 1)
  ⏳ Waiting 5s before next poll...

[Poll 2] Fetching...
[Poll 2] Received 2 events (pollIndex: 2)
  ⏳ Waiting 5s before next poll...

  ... (polls 3–8 continue similarly) ...

[Poll 9] Fetching...
[Poll 9] Received 1 events (pollIndex: 9)

── Total raw events collected: 15 ──

── Unique events after deduplication: 9 ──

── Leaderboard ──
┌──────┬────────────────────┬────────────┐
│ Rank │ Participant        │ Score      │
├──────┼────────────────────┼────────────┤
│    1 │ Bob                │        295 │
│    2 │ Alice              │        280 │
│    3 │ Charlie            │        260 │
└──────┴────────────────────┴────────────┘

  Total Score (all users): 835

── Submitting leaderboard... ──

── Submission Response ──
{"regNo":"RA2311003011950","totalPollsMade":10,"submittedTotal":835,"attemptCount":1}

📋 Submission recorded successfully.
```

### Final Leaderboard

| Rank | Participant | Total Score |
|:----:|-------------|:-----------:|
| 🥇 | **Bob** | **295** |
| 🥈 | **Alice** | **280** |
| 🥉 | **Charlie** | **260** |
| | **Grand Total** | **835** |

---

## 📁 Project Structure

```
bajaj-quiz-leaderboard/
├── QuizLeaderboard.java    # Single-file application — poll, dedup, aggregate, submit
├── README.md               # Documentation (this file)
└── .gitignore              # Ignores .class files, IDE configs
```

### Why Single-File?

For a focused task like this, a single well-structured Java file:
- Eliminates build-tool complexity (no Maven/Gradle setup)
- Is immediately compilable with `javac` on any JDK 11+ system
- Keeps the solution self-contained and easy to review

---

## 🛠 Tech Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Language** | Java 23 (compatible with JDK 11+) | Core application logic |
| **HTTP Client** | `java.net.HttpURLConnection` | REST API communication |
| **JSON Handling** | Custom lightweight parser | Zero-dependency JSON parsing |
| **Data Structures** | `HashSet`, `LinkedHashMap`, Java Streams | Deduplication, aggregation, sorting |

---

## 🔑 Key Design Decisions

### 1. Zero External Dependencies
The entire application uses only JDK built-in classes. This was a deliberate choice to demonstrate understanding of core Java networking and data structures without relying on frameworks like Spring Boot or libraries like Jackson/Gson.

### 2. Custom JSON Parser
Instead of pulling in a JSON library, a lightweight parser handles the specific response format. This keeps the project dependency-free while still correctly processing the API responses.

### 3. HashSet-Based Deduplication
Using `HashSet.add()` for deduplication provides **O(1)** amortized time complexity per lookup, making the dedup stage efficient even with large event volumes.

### 4. Stream API for Leaderboard
Java Streams provide a clean, functional approach to sorting and collecting the final leaderboard:

```java
List<Map.Entry<String, Integer>> leaderboard = scoreMap.entrySet().stream()
    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
    .collect(Collectors.toList());
```

---

<p align="center">
  <b>Built for Bajaj Finserv Health — JAVA Qualifier — SRM</b>
  <br>
  <sub>April 2026</sub>
</p>
