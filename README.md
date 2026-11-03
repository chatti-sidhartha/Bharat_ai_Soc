# LearnEdge — AI Learning Companion

An offline AI tutor for Android (Kotlin, Jetpack Compose), built for the Bharat SoC Challenge:
*Agentic AI for Personalized Academic Learning and Intelligent Tutoring*.

The app learns what a student wants to become, measures their skill gaps with a short diagnostic
quiz, and builds a day-by-day study plan. Llama 3.2 1B runs **on the phone** through ExecuTorch and
writes the daily tips. Everything works in airplane mode; no student data leaves the phone.

## Build

Requirements: Android Studio (AGP 9.4), JDK 17+, an arm64 Android phone (minSdk 24).

```sh
./gradlew testDebugUnitTest assembleDebug        # unit tests + debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Xiaomi/MIUI phones, the first install must be confirmed on the phone ("Install via USB").

## Model files

The model is **not** in the repo (`*.pte` and tokenizers are git-ignored). Download Llama 3.2 1B
Instruct, SpinQuant INT4, from Hugging Face
[`executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET`](https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET)
(~1.1 GB, not gated), then push both files to the app's external files folder:

```sh
adb push Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte /sdcard/Android/data/com.example.learnedge/files/model.pte
adb push tokenizer.model /sdcard/Android/data/com.example.learnedge/files/tokenizer.model
```

On Git Bash for Windows, set `MSYS_NO_PATHCONV=1` first, or the `/sdcard` paths get rewritten.
Uninstalling the app deletes these files, so push them again after a reinstall. File names and
search folders are set in `llm/ModelConfig.kt`. Without the files the app still works: tips fall back
to rule-based text, and the Tutor tab can use the Mock engine.

## Architecture

```
ui      Compose screens + ViewModels: onboarding, diagnostic, Today, Plan, Tutor, Progress
domain  pure Kotlin, no Android imports, unit-tested: knowledge graph, BKT, planner
data    Room database (schema in app/schemas), curriculum.json + diagnostic.json assets
llm     LlmEngine interface: ExecuTorch (on-device), Ollama (dev only), Mock; tips; benchmark
```

Flow:

1. **Onboarding** — the student picks a track (Java Backend Developer or DSA), rates each skill
   0–10, and sets minutes per day and a deadline.
2. **Knowledge graph** — topics are nodes and prerequisites are edges (`domain/KnowledgeGraph.kt`),
   seeded into Room from `assets/curriculum.json` (2 tracks, 64 topics).
3. **Diagnostic quiz** (18 questions per track) sets the starting mastery: the self-rating gives the
   prior, then **Bayesian Knowledge Tracing** updates it after every answer
   (`domain/Bkt.kt`: P(T)=0.15, P(S)=0.10, P(G)=0.20, mastered at P(L) ≥ 0.95).
4. **Planner** (`domain/StudyPlanner.kt`), deterministic:
   weighted skill gap `g = w · max(0, target − current) / 10` → priority Kahn topological sort
   (prerequisites always first) → time-budget scheduler over days × minutes/day that defers
   low-priority leaf topics when they don't fit before the deadline.
5. **Today / Plan** — marking a topic done applies one BKT learning step and the plan regenerates.
6. **LLM writes text only.** The algorithms decide what to study, in what order, and when. The model
   only writes the day's summary and per-topic tips (`llm/tips`). Output must be valid JSON with
   known topic IDs and sane lengths; it is retried at most twice with a stricter prompt, then falls
   back to rule-based text. Each tip is labelled **AI** or **Rule-based** in the UI.

Every model call is logged to `files/benchmarks.csv` on the phone (TTFT, tokens/s, peak PSS,
battery temperature). Tutor → ⋮ → **Benchmark** runs 3 prompts × 3 runs.

## Performance (Xiaomi 2312DRA50I, airplane mode, 3 Nov 2026)

| Prompt | TTFT (ms) | Decode (tok/s) | Tokens | Peak PSS (MB) |
|---|---|---|---|---|
| short | 821 | 15.0 | 35 | 1395 |
| medium | 985 | 14.1 | 295 | 1401 |
| long | 2195 | 12.3 | 714 | 1404 |

Model load: 5.0 s. Values are means of 3 runs.

## Known limitations

- The 1B model often writes malformed JSON (e.g. a repeated `"tip"` key), so tips frequently need a
  retry, and some topics end up with rule-based tips.
- Prompts for tips are ~1,200–1,500 characters, so time to first token is 6–7 s and the whole tip
  generation takes 15–50 s in the background.
- The diagnostic quiz has 3 questions per skill; an answer updates every topic in that skill, not
  just the topic the question was about.
- The scheduler packs topics strictly in path order, so a day can end below the time budget.
- There are no per-topic quizzes yet, so after onboarding, mastery only changes when a topic is
  marked done (one BKT learning step). The Progress tab is a placeholder.
- The model loads at app start and holds ~1.4 GB of RAM; the temperature is fixed at 0.7.
- The battery temperature in the benchmark comes from the last battery broadcast and barely changes.
