# Demo script — recording the Memory Recall Assistant

A ~2-minute walkthrough that shows every part of the app working **live and fully offline**:
recognition, the self-writing diary, the spoken reminder, grounded answers, and why the app ignores
bystanders. Two people: **You** (holding the phone, the app's user) and **Maya** (swap in your friend's
real name everywhere).

---

## Before you record

**Phone (one-time):**
- [ ] Install a **release** build: `flutter run --release` (debug builds stutter on camera).
- [ ] **Clear all data** (Debug tab). Don't use *Seed demo data* — it replaces everything.
- [ ] Turn on **Airplane mode** (Wi-Fi off too) and run one Recall to confirm it still works offline —
      this is the strongest single proof that nothing leaves the phone.
- [ ] Media volume up (the spoken reminder uses media volume). Silence notifications (Do Not Disturb).
- [ ] Quiet room. Speech recognition is the most fragile part — avoid music or a TV in the background.

**Capturing the video:**
- **Screen:** the phone's built-in screen recorder, set to **Device audio** (so the spoken reminder is
  recorded). Avoid "Microphone" mode — it can compete with the app for the mic. Do a 10-second test.
- **The human side (recommended):** a second phone filming over your shoulder, so viewers see the
  real person *and* the app's screen. Picture-in-picture in editing works great.

**How the app behaves (so the take works first time):**
- Only the face **inside the circle** is identified. Hold the phone at chest height, about an arm's
  length to 1.5 m away, face filling roughly a fifth of the frame.
- Speech is turned into text after each **pause**: speak in short sentences and leave half a beat
  between them.
- A conversation is one diary entry as long as there's never more than ~60 s of silence/no-face.
- The suggested question's *topic* is the **longest word** in the conversation. The script makes it
  **"Philadelphia"**. Avoid other words of 12+ letters while recording (e.g. *congratulations,
  unfortunately, conversation, presentation, neighborhood*) or one of those becomes the topic.

---

## Shot list

### 0 · Hook (voice-over, ~10 s)
> "For someone living with early memory loss, a familiar face can come with a blank: who is this, and
> what did they tell me last time? This app is a memory aid that runs entirely on the phone. No cloud —
> it's in airplane mode right now."

(If you have a personal reason for building this, this is the place to say it in your own words.)

Show the airplane-mode icon in the status bar.

### 1 · Enroll Maya (~15 s)
1. **Recall tab → Start camera.** The live view appears with the white targeting circle.
2. **Enroll tab → camera button**, type **Maya**, keep her face in the circle for ~3 seconds.
3. Confirmation: *Enrolled "Maya" … from 5 frames.*

> VO: "First, Maya is enrolled — five quick frames of her face, stored only on this phone."

### 2 · The conversation (~45 s)
Go back to the **Recall** tab so the live view is on screen: Maya's box turns **green** with her name
and match score. Then talk naturally — keep her roughly in the circle, but it's fine if the camera
drifts for a few seconds.

> **You:** Hey Maya! How have you been?
>
> **Maya:** Busy! I have a job interview in Philadelphia next Tuesday.
>
> **You:** That's huge. Which company?
>
> **Maya:** A small robotics startup. I'm nervous about the coding round.
>
> **You:** You'll be great. How's Biscuit doing after the knee surgery?
>
> **Maya:** Much better, he's walking again. The vet says two more weeks of rest.
>
> **You:** Good to hear. Let's get coffee at Blue Door on Sunday and you can tell me how it went.
>
> **Maya:** Deal. Sunday at ten.

> VO (over the end): "While we talk, the app is quietly writing a diary entry — who I was with, when,
> and what we said."

Optional cutaway: the **Timeline** tab, showing **one** entry for Maya with the conversation text.

### 3 · Time skip (title card, ~2 s)
> **"Later that day…"**

Wait a few minutes before the next shot so the reminder says *"a few minutes ago"* rather than *"just
now"* — or go for it the next day for maximum effect (*"yesterday"*).

### 4 · The recall moment (~20 s) — the money shot
Face Maya so she's in the circle (green box) and press **Recall**.

The phone **speaks** a reminder, something like:
> *"This is Maya — a few minutes ago you talked about the job interview in Philadelphia and Biscuit's
> recovery."*

> VO: "When I see her again, one tap tells me who she is and what we talked about — out loud, so I
> don't even have to look at the screen. On the glasses, this plays in your ear."

### 5 · Ask a follow-up (~15 s)
Tap **"What did Maya and I talk about regarding Philadelphia?"** The answer is spoken and appears with
**evidence cards** (the diary entries it came from — time, place, transcript snippet).

> VO: "Answers come only from my own memories, and each one shows where it came from — so it can't
> make things up."

You can also tap **"When did I last see Maya?"**.

### 6 · Someone new (~10 s)
Point at a person who isn't enrolled. The box turns **red**; press **Recall** → the phone says
*"I don't recognize this person yet."*

> VO: "Someone it hasn't met yet? It says so — and I can add them from the Enroll tab."

### 7 · Bystanders are ignored (~10 s)
Put Maya at the **edge** of the frame with nobody in the circle: no box, nothing identified.

> VO: "It only looks at the person I'm facing — not everyone around me."

### 8 · Close (~10 s)
> VO: "Face recognition, speech-to-text and the language model all run on the phone. We built the
> first version in 24 hours at the Qualcomm × Meta hackathon for smart glasses — this is it running on
> a Pixel today."

---

## What you'll see (and what it means)

| On screen | Meaning |
| --- | --- |
| White circle, no box | No face near the center — nothing is identified. |
| Green box + "Maya · 9x% match" | Recognized. The % is face similarity (anything above ~40% counts as a match). |
| Red box + "Unknown person" | A centered face that isn't enrolled. |
| Answer starts with "Most recent memory…" | Gemini Nano wasn't available, so the app showed a plain summary instead. |

The exact wording of the spoken reminder and answers comes from Gemini Nano and varies between
takes — record two or three and keep the best.

## If something goes wrong

- **Reminder doesn't mention the conversation:** open **Timeline** — the conversation should be in
  Maya's entry. If it's in a separate "no person" entry, more than a minute passed between talking and
  her face being in the circle; re-record with her in view while you talk.
- **Nothing in the transcript:** check `adb logcat -s LiveSpeech`; speak a little louder and pause
  between sentences.
- **Maya shows as Unknown:** re-enroll in similar lighting, face straight on, filling more of the frame.
