# Quiz Leaderboard System — Bajaj Finserv Health

![Java 23](https://img.shields.io/badge/Java-23-orange.svg)
![Zero Dependencies](https://img.shields.io/badge/Dependencies-Zero-green.svg)
![Build Passing](https://img.shields.io/badge/Build-Passing-brightgreen.svg)
![Bajaj Finserv](https://img.shields.io/badge/Qualifier-SRM-blue.svg)

A backend system that consumes quiz API responses from a distributed validator, handles duplicate event delivery, builds an accurate leaderboard, and submits the result — all in a single-file, zero-dependency Java application.

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

```text
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
The application makes 10 sequential HTTP GET requests to the validator API, one for each poll index (0–9). A mandatory 5-second delay is enforced between requests to comply with rate-limiting requirements.

**Step 2 — Collection**
All events from every poll response are accumulated into a single list. At this stage, duplicates are still present in the raw data.

**Step 3 — Deduplication**
Using a `HashSet` with composite keys (`roundId|participant`), the system filters out duplicate events.

**Step 4 — Aggregation**
Scores are aggregated per participant using a `Map<String, Integer>` with `merge()` or `put()`.

**Step 5 — Sorting & Submission**
The leaderboard is sorted in descending order by total score and submitted via a single `POST` request.

---

## 🔁 Deduplication Strategy

### The Problem
In distributed systems, **at-least-once delivery** is common — the same message can arrive multiple times. If duplicates are processed, the final score will be inflated:

```text
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
String key = roundId + "|" + participant;

if (seen.add(key)) {
    // Process unique event...
}
```

### Why This Works
| Property | Guarantee |
|---|---|
| **Uniqueness** | `roundId + participant` uniquely identifies a scoring event |
| **Idempotency** | Processing the same event multiple times yields the same result |
| **O(1) Lookup** | `HashSet.add()` provides constant-time duplicate detection |

---

## 📡 API Reference
**Base URL:** `https://devapigw.vidalhealthtpa.com/srm-quiz-task`

### `GET /quiz/messages`
**Query Parameters:**
| Parameter | Type | Required | Description |
|---|---|---|---|
| `regNo` | `string` | ✅ | Student registration number |
| `poll` | `int` | ✅ | Poll index (`0–9`) |

---

### `POST /quiz/submit`
**Request Body:**
```json
{
  "regNo": "RA2311028010087",
  "leaderboard": [
    { "participant": "Bob", "totalScore": 295 },
    { "participant": "Alice", "totalScore": 280 }
  ]
}
```

---

## 🚀 Getting Started

### Prerequisites
* **Java JDK 11+** (Tested with OpenJDK 23)
* **Zero external dependencies** — only JDK built-in classes (`java.net.http.*`, `java.util.*`) are used.

### Clone & Run
```bash
# Compile
javac QuizLeaderboard.java

# Run
java QuizLeaderboard
```

### Configuration
Update the constant in `QuizLeaderboard.java`:
```java
private static final String REG_NO = "RA2311028010087"; 
```

---

## 🖥 Sample Execution
```text
═══════════════════════════════════════════════════════════
  Quiz Leaderboard System (Java)
  Registration No: RA2311028010087
═══════════════════════════════════════════════════════════

[Poll 0] Fetching...
[Poll 0] Received 2 events
   ⏳ Waiting 5s before next poll...

... (polls 1–9) ...

── Total raw events collected: 15 ──
── Unique events after deduplication: 9 ──

── Leaderboard ──
┌──────┬────────────────────┬────────────┐
│ Rank │ Participant        │ Score      │
├──────┼────────────────────┼────────────┤
│    1 │ Bob                │        295 │
│    2 │ Alice              │        280 │
└──────┴────────────────────┴────────────┘

📋 Submission recorded successfully.
```

---

## 📁 Project Structure
```text
bajaj-quiz-leaderboard/
├── QuizLeaderboard.java    # Single-file application — poll, dedup, aggregate, submit
├── README.md               # Documentation (this file)
└── .gitignore              # Standard git configuration
```

---

## 🛠 Tech Stack
| Layer | Technology | Purpose |
|---|---|---|
| **Language** | Java 23 (JDK 11+) | Core application logic |
| **HTTP Client** | `java.net.http.HttpClient` | REST API communication |
| **JSON Handling** | Custom Regex Parser | Zero-dependency JSON parsing |
| **Data Structures** | `HashSet`, `HashMap` | Deduplication and aggregation |

---

## 🔑 Key Design Decisions
1. **Zero External Dependencies**: Demonstrates mastery of core Java APIs without relying on external libraries like Jackson or Gson.
2. **Custom JSON Parser**: Uses lightweight regex to extract fields, ensuring the code remains portable and build-tool independent.
3. **Modern Java Features**: Uses the `HttpClient` API (introduced in Java 11) for clean, asynchronous-capable network code.

---
<p align="center">
  <b>Built for Bajaj Finserv Health — JAVA Qualifier — SRM</b>
  <br>
  <sub>April 2026</sub>
</p>
