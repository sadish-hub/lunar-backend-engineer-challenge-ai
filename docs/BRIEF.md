# Lunar Backend Engineer Challenge — Brief

Faithful capture of Lunar’s Rockets take-home README. Do not treat this as invented requirements; prefer the source wording. Source: `/cursor/stores/self/internal/challenge-readme.md`.

---

## Introduction

Thank you for taking Lunar's backend engineer code challenge!

In the ZIP-file you will find a `README.md` and folders containing executables for various operating systems and architectures.

> **Important:** If you cannot find an executable that works for your system, please reach out as soon as possible so they can get you one that works.

---

## Assessment

Submission is assessed on criteria such as:

- **Design choices and trade-offs**: how clearly you explain the choices you made, how well the design supports future requirements such as scale, and which limitations you consciously accepted in favor of a simpler solution.
- **Depth and maintainability of the implementation**: your choices and reasoning around data structures, concurrency, persistence, and error handling; how understandable the code is; and how maintainable the solution is. They may drill into specific parts of your code.
- **Verification**: what you did to show the code works, e.g. automated tests.

### AI

They **encourage** full use of AI while solving this challenge. Coding assistants, agents, and research are all fair game. Treat this as a realistic example of day-to-day work.

**Use of AI is part of how they assess the challenge.** They are not scoring "how much AI you used" as a quantity. They are looking for a **deliberate, accountable workflow**: what you delegated, what you verified yourself, and how you kept ownership of correctness and design. This sits alongside the solution itself. They expect you to understand the problem space deeply and to stand behind the code you submit.

Be prepared to cover the following in the interview:

- **Describe how you used AI at a high level**: what kinds of tasks you delegated, what you verified yourself, and where you overrode or rewrote AI output.
- **Reflect on agentic coding**: what worked well, what was risky, how you kept quality high through reviews, tests, and incremental steps, and what you would do differently in a larger or production system.

A strong submission is one where the implementation holds up under technical scrutiny and you can clearly articulate your understanding, your verification habits, and a thoughtful approach to working with AI.

---

## The Challenge

Build one or more services that consume messages from a set of _rockets_ and expose their state through a REST API. The API is imagined for something like a dashboard; you are **not** expected to build the dashboard itself.

### Minimum API expectations

At a minimum, endpoints that can:

1. Return the current state of a given rocket (type, speed, mission, etc.)
2. Return a list of all the rockets in the system; preferably with some kind of sorting.

The service should also expose an endpoint that the test program can post messages to (see [Running the test program](#running-the-test-program)).

### Language

Lunar writes services in [Go](https://go.dev/), but there are **no constraints** on which language you choose. They would rather see a great solution in a language you are comfortable with than a mediocre solution written in Go.

---

## The messages

Each rocket dispatches various messages (encoded as JSON) about its state changes through individual radio _channels_.

- The **channel** is unique for each rocket and can therefore be treated as the ID of the rocket.
- In addition to the channel, each message contains a **message number** (order within a channel), a **message time** (when the message was sent), and a **message type** (the event that occurred).

### Delivery semantics (important)

- Messages will arrive **out of order**.
- Delivery follows an **at-least-once guarantee** — you may receive the same message more than once.
- Messages are **redelivered** if not successfully delivered, e.g. when receiving a **non-2xx** HTTP status code.

### Envelope example (`RocketLaunched`)

```json
{
    "metadata": {
        "channel": "193270a9-c9cf-404a-8f83-838e71d9ae67",
        "messageNumber": 1,
        "messageTime": "2022-02-02T19:39:05.86337+01:00",
        "messageType": "RocketLaunched"
    },
    "message": {
        "type": "Falcon-9",
        "launchSpeed": 500,
        "mission": "ARTEMIS"
    }
}
```

### Message types and payloads

#### `RocketLaunched`

Sent out once: when a rocket is launched for the first time.

```json
{
    "type": "Falcon-9",
    "launchSpeed": 500,
    "mission": "ARTEMIS"
}
```

#### `RocketSpeedIncreased`

Continuously sent out: when the speed of a rocket is increased by a certain amount.

```json
{
    "by": 3000
}
```

#### `RocketSpeedDecreased`

Continuously sent out: when the speed of a rocket is decreased by a certain amount.

```json
{
    "by": 2500
}
```

#### `RocketExploded`

Sent out once: if a rocket explodes due to an accident/malfunction.

```json
{
    "reason": "PRESSURE_VESSEL_FAILURE"
}
```

#### `RocketMissionChanged`

Continuously sent out: when the mission for a rocket is changed.

```json
{
    "newMission": "SHUTTLE_MIR"
}
```

---

## Running the test program

In the ZIP file, locate the executable that matches your system and run:

```bash
./rockets launch "http://localhost:8088/messages" --message-delay=500ms --concurrency-level=1
```

This launches the program, which starts posting (request method: `POST`) messages to the URL provided with a delay of 500ms between each message.

- To see all commands: `./rockets help`
- For help on `launch`: `./rockets launch --help`

> They are going to run the program against your solution with the **default values**.

Default URL implied by the example: `http://localhost:8088/messages` (POST).

---

## Submitting your solution

- Do not expect to spend more than **~6 hours** on this challenge.
- If you do not succeed in completing everything, submit what you have — better than nothing.
- Include all necessary files and information for running and assessing the solution.
- Submit either a ZIP-file or a link to an online VCS provider (GitHub, GitLab, Bitbucket).

---

## On the day of the interview

- Bring your own computer and prepare to present your solution.
- They look for how you went about solving the problem, which challenges you met, how you solved them, and the design choices you made.
- Go **breadth-first**, and aim for **~10 minutes**. They will ask questions about the details.

---

## Notes for Driver/Car (process, not challenge invent)

These are process constraints already agreed for this project; they are **not** additional Lunar README requirements:

- Driver: Sadishkumar; agents propose only until Driver OK.
- Lean stack preference: Java + Spring Boot REST; scaffold is phase 0; final stack TBD with Driver.
- Spec-driven plans later under `docs/plans/`; master plan after requirements clarity; Driver decides phases.
- YAGNI: do not assume auth, CORS, uniqueness beyond channel-as-ID, OWASP, datepickers, extra frameworks unless marked OPEN.
- No hand-in / push yet.
- Test harness posts to `http://localhost:8088/messages` by default; they run with default values.
