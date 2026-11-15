# LearnEdge — Project Context

## 1. Project

**Title:** Agentic AI for Personalized Academic Learning and Intelligent Tutoring (Bharat SoC Challenge).
**App name:** LearnEdge, an offline AI tutor Android app (Kotlin, Jetpack Compose).

The problem statement asks for an AI learning companion that understands learner goals, identifies
skill gaps, recommends personalized learning pathways, generates study plans and adapts over time.

**Mandatory:** a Llama model running ON the phone via ExecuTorch (`org.pytorch:executorch-android:1.5.1`)
on an Arm Android smartphone.

**Deliverables:** source code, Android app, demonstration video, technical report.
**Submission deadline:** 23 November 2026 (confirmed by the user on 15 Nov 2026; earlier notes said 30 Nov).

## 2. Fixed design (approved in the review documents; do not change)

- **Knowledge graph.** The student is modelled as a knowledge graph: topics are nodes, prerequisites
  are edges, and each topic stores the student's quiz scores and mastery P(L). Stored in Room (SQLite)
  on the phone.
- **Diagnostic quiz** at onboarding sets the starting mastery.
- **Bayesian Knowledge Tracing (BKT)** updates mastery after every quiz answer.
  Defaults: P(T)=0.15, P(S)=0.10, P(G)=0.20; mastered when P(L) >= 0.95.
- **Planner** (deterministic, pure Kotlin):
  1. weighted skill gap `g = w * max(0, target - current) / 10`
  2. priority topological sort (Kahn), so prerequisites always come first
  3. time-budget scheduler (days x minutes/day) that defers low-priority topics if they don't fit.
- **The algorithms decide what, in what order and when. The LLM (Llama 3.2 1B on the phone) only
  WRITES text** (explanations, tips, quizzes). All structured LLM output is validated (JSON schema +
  known topic IDs), retried at most twice, then falls back to rule-based text.
- **Privacy:** everything must work in airplane mode. Never send student data off the phone.

## 3. Architecture

Package `com.example.learnedge`:

| Layer | Contents | Rule |
|---|---|---|
| `ui` / `chat` | Compose screens + ViewModels (MVVM) | |
| `domain` | BKT, knowledge graph, planner | Pure Kotlin, no Android imports, fully unit-tested |
| `data` | Room database, curriculum assets | |
| `llm` | `LlmEngine` interface + `OllamaLlmEngine`, `ExecuTorchLlmEngine`, `MockLlmEngine` | Keep all three working |

Key files:
- `llm/ModelConfig.kt`: model file names, search folders, temperature, `SEQ_LEN`.
- `llm/PromptFormat.kt`: Llama 3 Instruct chat template.
- `llm/OllamaLlmEngine.kt`: dev-only engine; talks to Ollama on the laptop at `localhost:11434`
  through `adb reverse tcp:11434 tcp:11434`. Not used for the airplane-mode demo.
- `domain/Bkt.kt`, `domain/KnowledgeGraph.kt`: BKT update and validated prerequisite graph.

## 4. Working rules

- Make small, working steps. Build and run on the phone after each step.
- Write unit tests for all domain logic (`app/src/test/.../domain`).
- **Never commit model files** (`.pte`, tokenizer). They are git-ignored.
- Ask before downloading anything large or deleting anything.
- At the end of each step, say exactly what to check on the phone, then commit with a clear message.

Build and test: `./gradlew testDebugUnitTest assembleDebug`
Install: `adb -s d5cd21da install -r app/build/outputs/apk/debug/app-debug.apk`

## 5. Device

- **Phone:** Xiaomi `2312DRA50I` (adb serial `d5cd21da`), Android 16, arm64-v8a, 12 GB RAM,
  about 13 GB free storage (checked 3 Nov 2026). An emulator (`emulator-5554`) may also be attached,
  so always pass `-s d5cd21da`.
- **PC:** Windows, Android Studio, SDK, NDK, CMake, Python 3.12, `hf` CLI.

## 6. On-device model

- **Model:** Llama 3.2 1B Instruct, SpinQuant INT4 (XNNPACK, CPU), from Hugging Face
  `executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET` (not gated, ~1.1 GB).
- **Local copy (outside the repo):** `C:\Users\chatt\learnedge-models\llama-3.2-1b-spinquant\`
- **On the phone:** `/sdcard/Android/data/com.example.learnedge/files/model.pte` and `tokenizer.model`
  (names set in `ModelConfig`). Push with:
  ```
  adb -s d5cd21da push <file>.pte /sdcard/Android/data/com.example.learnedge/files/model.pte
  adb -s d5cd21da push tokenizer.model /sdcard/Android/data/com.example.learnedge/files/tokenizer.model
  ```
- Uninstalling the app deletes these files; push them again after a reinstall.

## 7. Performance measurements (for the SoC report)

Model size, load time, time to first token, tokens/second, RAM usage, CPU utilization,
temperature/battery where practical, and offline (airplane-mode) operation. The chat screen
already shows load time, TTFT and tokens/s.

## 8. Status (3 Nov 2026)

Done:
- Llama 3.2 1B runs on the phone offline. Benchmark (3 runs/prompt): TTFT 0.8–2.1 s, decode 13–15 tok/s,
  peak PSS ~1.36 GB, load 4.7 s. Benchmark menu in Tutor (⋮) writes `files/benchmarks.csv`.
- App shell: bottom bar Today / Plan / Tutor (chat, 3 engines) / Progress (placeholder).
- Curriculum (2 tracks, 64 topics) seeded into Room; onboarding; hand-written diagnostic (36 questions)
  with BKT initial mastery; deterministic planner and scheduler; Today and Plan screens with
  mark-done (one BKT learning transition) and automatic regeneration.
- AI daily tips on Today (JSON validation, 2 retries, rule-based fallback, AI/Rule-based labels).
- Room schema v4 with auto-migrations (schemas in `app/schemas`).
- v0.3-daily-plan: full airplane-mode E2E on the phone passed; README.md has build steps,
  model setup, architecture, latest benchmark and known limitations.

Next:
1. LLM text: explanations and quizzes per topic with JSON validation, 2 retries, rule-based fallback.
2. Topic quizzes that update mastery with BKT; Progress screen.
3. Report, demo video, submission.

Plan rules worth knowing: done items are kept as history; a topic counts as studied once its done
minutes reach its estimate; packing is strictly in path order, so a day can end below the budget
when the next topic doesn't fit.

## 9. Roadmap to submission

Set by the user on 15 Nov 2026 after a 12-day gap. Follow exactly.

### Standing orders

1. The locked design in section 2 does not change: algorithms decide what and when; the LLM only
   writes text; every LLM output is validated, retried at most twice, then replaced by bundled or
   rule-based content. Everything works in airplane mode.
2. One prompt = one milestone. Do not start work from a later milestone. Do not refactor unrelated code.
3. Domain logic stays pure Kotlin with unit tests. All tests pass before every commit. Room changes
   use migrations.
4. Every milestone ends with: a check on the phone in airplane mode with the On-device engine, a
   commit, and a report to the user (what works, what you verified on the phone, what you could NOT
   verify, and any numbers you measured).
5. Never invent a benchmark number, test result or accuracy figure. If you did not measure it, say so.
6. Ask the user before downloading anything large, deleting data, or pushing to a remote.
7. If a milestone takes more than twice its budget, stop and tell the user what to cut.
8. Feature freeze is 25 Nov. After that: bug fixes, measurements, report and video only.

### Milestones

M1 Lessons + question bank -> M2 Topic quiz + BKT loop -> M3 Progress, knowledge map, "Why this?"
-> M4 Night Shift pre-generation -> M5 Hardening + final benchmarks -> M6 Technical report
-> M7 Demo video -> M8 Submission.

Submission deadline: 23 Nov 2026 (confirmed by the user). The feature freeze of 25 Nov in standing
order 8 falls after that date, so the user is finishing the milestones as fast as possible instead of
one per day; the freeze date has not been revised by the user.

### M1 status (15 Nov 2026): done
- `curriculum.json` topics carry 3 `keyPoints`; `question_bank.json` has 192 hand-written questions
  (3 per topic, difficulty 1-3 assigned by a text heuristic, not by testing students).
- Explanations are plain text (40-200 words), written by the on-device model from the key points,
  the learner's mastered prerequisites, what the topic unlocks and a level band from P(L)
  (<0.3 beginner, <0.7 intermediate, else revision). Validated, 2 retries, then the bundled key points.
- Room v6: `generated_content` (topicId, kind, payload, source, levelBand, promptVersion, modelId,
  verified, createdAt) replaced `topic_lessons`. `ContentRepository.getExplanation(topicId)`.
- `LlmEngine.generate(prompt, temperature, onToken)`: ExecuTorch takes temperature per call.
- Debug hook: create `files/force_validation_failure` on the phone (debug build only) to force every
  validation to fail and show the bundled fallback.
- Known limits: the validator checks form, not accuracy (a poor analogy passes); the correct option is
  strictly the longest in 46% of bank questions (ratchet test, target <= 35%).

### M2 status (15 Nov 2026): done, tag v0.4-quiz-loop
- Lesson screen -> "Take quiz": 3-5 bank questions (unseen / least recently seen first, easiest first,
  reported ones never). Each answer = one BKT update with the locked parameters, stored in
  `question_attempts` (questionId, topicId, chosenIndex, correct, timestamp, pLBefore, pLAfter), then
  right/wrong + the bank explanation. Summary shows mastery before -> after; P(L) >= 0.95 = mastered;
  the plan is then rebuilt with `PlanRepository.regenerate(FROM_TOMORROW)` (same as "mark done", which is unchanged).
- "Report this question": stored in `reported_questions`; the topic's quiz history is replayed from the
  mastery before that question's first attempt without it (`AttemptHistory.replay`), later attempts are re-chained.
- Room v7 (auto-migration 6->7). Domain: `Quiz.kt` (QuizSelector, AttemptHistory); tests in `QuizLoopTest`.
- Known limits: the bank has only 3 questions per topic, so "unseen first" cannot really work after one
  quiz and a reported question leaves a 2-question quiz; today's plan items are kept as they are
  (FROM_TOMORROW); a report does not replay mastery changes from other sources (e.g. "mark done") made after it.
