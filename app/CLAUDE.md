# LearnEdge — Project Context

## 1. Project

**Title:** Agentic AI for Personalized Academic Learning and Intelligent Tutoring (Bharat SoC Challenge).
**App name:** LearnEdge, an offline AI tutor Android app (Kotlin, Jetpack Compose).

The problem statement asks for an AI learning companion that understands learner goals, identifies
skill gaps, recommends personalized learning pathways, generates study plans and adapts over time.

**Mandatory:** a Llama model running ON the phone via ExecuTorch (`org.pytorch:executorch-android:1.5.1`)
on an Arm Android smartphone.

**Deliverables:** source code, Android app, demonstration video, technical report.
**Submission deadline:** 30 November 2026.

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
- Dev environment, phone over ADB, git repo (`master`).
- Chat app with engine selector (Ollama / On-device / Mock), streaming, stop, stats.
- Domain core: BKT + knowledge graph with 17 unit tests.

Next:
1. Run Llama 3.2 1B on the phone in airplane mode; record load time, TTFT, tokens/s.
2. Planner (gap -> Kahn priority order -> time-budget scheduler) with tests.
3. Room storage for the knowledge graph and progress; curriculum assets.
4. Diagnostic quiz -> BKT -> plan screens.
5. LLM text generation with JSON validation, 2 retries and rule-based fallback.
6. Benchmarks, report, demo video, submission.
