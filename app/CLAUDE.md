# LearnEdge — Project Context and Current Progress

## 1. Project Overview

**Project name:** LearnEdge — On-device Personalized AI Learning Companion

**Problem statement objective:** Develop an AI learning companion that creates personalized learning pathways based on learners' goals, interests, and current skill levels.

### Required features
- Understand learner goals.
- Identify skill gaps.
- Recommend personalized learning pathways.
- Generate study plans.
- Adapt recommendations over time.

### Mandatory technologies
- Llama-family model for advisory/reasoning.
- ExecuTorch as the on-device reference runtime.
- Arm-based Android smartphone.

### Deliverables
- Source code.
- Android application.
- Demonstration video.
- Technical report.

**Submission deadline:** 30 November 2026.

## 2. Core Idea

Build an Android application that runs AI locally on the phone instead of depending on cloud AI APIs.

Example user profile:
- Goal: Become a Java backend developer.
- Current skills: Java 6/10, SQL 4/10, Spring Boot 2/10.
- Available study time: 2 hours daily.
- Deadline: 60 days.

The app should identify skill gaps, create a roadmap, generate daily study plans, provide tutoring and quizzes, and adjust recommendations using quiz performance and completed lessons.

## 3. How It Relates to SoC

An SoC (System on Chip) integrates processing components into a chip, such as the CPU, GPU and potentially an AI accelerator.

The intended execution pipeline is:

Android app → ExecuTorch → Llama model → phone's Arm hardware → generated response.

The Android app is the product interface. The on-device AI inference and its performance optimization are the SoC engineering component.

Planned performance measurements:
- Model size.
- Model loading time.
- Time to first token.
- Generation speed in tokens/second.
- RAM usage.
- CPU utilization.
- Temperature and battery impact, where practical.
- Offline operation.

The first implementation will target CPU inference with XNNPACK. Hardware acceleration can be explored later if supported by the phone and runtime.

## 4. Planned Technology Stack

### Android
- Android Studio.
- Kotlin.
- Jetpack Compose.
- Android SDK.
- Android NDK and CMake.
- ADB for device communication and debugging.

### On-device AI
- Llama 3.2 Instruct as the initial model family under consideration.
- ExecuTorch Android runtime.
- XNNPACK CPU backend.
- Compatible `.pte` model artifact and matching tokenizer.

### Learning system
- Local learner profile.
- Curriculum and prerequisite data.
- Skill-gap calculation.
- Study-plan generation.
- Quiz and progress tracking.
- Adaptive recommendations.

Room/SQLite can store learner progress locally. No cloud backend is required for the initial version.

## 5. Device and Development Environment

### Phone
- Model identifier reported: `2312DRA50I`.
- Android 16.
- 12 GB RAM was reported from the phone information.
- ADB successfully detects the phone.
- Architecture check returned `arm64-v8a`.

### PC
- Windows.
- Android Studio installed.
- Python installed.
- Android SDK Platform-Tools installed.
- Android SDK Build-Tools installed.
- Android NDK (Side by side) installed.
- CMake installed.
- ADB command works.

The PC has approximately 30 GB of free storage, according to the user.

**Phone's free internal storage has not yet been confirmed.**

## 6. Completed Work

1. Installed and opened Android Studio.
2. Configured ADB on Windows by adding Android SDK Platform-Tools to PATH.
3. Connected the phone over USB.
4. Ran `adb devices` and received a device identifier with status `device`.
5. Ran `adb shell getprop ro.product.cpu.abi` and received `arm64-v8a`.
6. Verified the required SDK tools were installed.
7. Created an Android Studio project named `LearnEdge` using Kotlin and Jetpack Compose.
8. Successfully ran the default Android application on the phone.
9. Opened `build.gradle.kts` for the app module.
10. Added the following dependency:
    `implementation("org.pytorch:executorch-android:1.5.1")`
11. Gradle synchronization completed without errors.

The default app still displays its starter screen. Llama has NOT yet been integrated or run.

## 7. Model Choice Under Consideration

The latest proposed starting model is **Llama 3.2 3B Instruct, using a compatible quantized artifact**.

Reason: it may offer better tutoring and reasoning than a 1B model while remaining a possible mobile deployment target.

However:
- No model has been downloaded yet.
- No `.pte` file or tokenizer has been installed.
- The exact compatible model artifact, quantization, runtime version and supported operators still need verification.
- The phone's 12 GB RAM does not by itself guarantee satisfactory performance.
- The PC's 30 GB free storage is not the same as available phone storage.

Use the official ExecuTorch documentation and examples to select compatible artifacts:
- Android guide: https://docs.pytorch.org/executorch/stable/using-executorch-android.html
- LLM Android guide: https://docs.pytorch.org/executorch/stable/llm/run-on-android.html
- Example repository: https://github.com/meta-pytorch/executorch-examples/tree/main/llm/android/LlamaDemo

Do not download an arbitrary Hugging Face Llama checkpoint and assume it can run directly. The Android runtime requires compatible exported artifacts and a matching tokenizer.

## 8. Planned Development Workflow

### Phase 1 — Prove on-device inference
- Confirm available internal phone storage.
- Verify the Android runtime version and compatible example.
- Obtain a compatible Llama model artifact and tokenizer.
- Load the model using the supported Android API.
- Run a simple prompt on the physical phone.
- Verify that inference works without an internet connection.

### Phase 2 — Build the AI chat interface
- Create a simple chat screen in Jetpack Compose.
- Connect it to the local model.
- Display generated responses.
- Handle loading states, errors and long responses.

### Phase 3 — Build learner personalization
- Collect goals, interests, current skill levels, deadline and daily availability.
- Store learner data locally.
- Build a structured curriculum and prerequisite map.
- Calculate skill gaps using deterministic logic.

### Phase 4 — Generate learning pathways
- Pass structured learner information to Llama.
- Generate personalized roadmaps and study plans.
- Validate the model output before displaying it.

### Phase 5 — Adaptive learning
- Add quizzes and topic completion tracking.
- Track scores and weak areas.
- Recalculate priorities and adjust future study plans.

### Phase 6 — SoC benchmarking
- Measure latency, generation throughput, memory usage and thermal behaviour.
- Compare configurations such as quantization or supported execution backends where feasible.
- Document hardware, runtime, model and test conditions.
- Demonstrate offline inference.

### Phase 7 — Final submission
- Test and polish the Android application.
- Organize source code and README.
- Prepare the technical report.
- Record the demonstration video.
- Complete submission before 30 November 2026.

## 9. Proposed Team Division

If the team has three members:

1. **AI/runtime:** Llama, ExecuTorch, model integration and benchmarking.
2. **Android:** Kotlin, Compose, UI, storage and runtime integration.
3. **Learning intelligence:** curriculum, skill-gap detection, study planning, quizzes and adaptation.

For a smaller team, combine these responsibilities.

## 10. Immediate Next Action

Before downloading the model, check the phone's free internal storage:

**Settings → Storage**

Tell ChatGPT how much free storage is available. Then select a model artifact and tokenizer that are compatible with the ExecuTorch Android runtime and the target device.

**Current status:** The development environment is ready, the physical phone is connected, the starter Android app runs, and the ExecuTorch dependency has synchronized. The next milestone is getting a compatible Llama model to generate its first response directly on the phone.
