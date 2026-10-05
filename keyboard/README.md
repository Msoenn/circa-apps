# Circa Keyboard (`org.circa.keyboard`)

A round, full-screen input method for the 384x384 screen: letter, number and symbol layouts sized from the circle's
diameter (`KeyboardLayout`), word completion from a bundled frequency list plus words typed before (`Suggester`),
a repeating delete key, and a swipe right to hide the keyboard. It is the only system IME in a Circa image, so the system selects it on
its own. A bare `android.view.View`, no AndroidX, no permissions.

The word list `app/src/main/res/raw/words_en.txt` is the top of the AOSP LatinIME English word list (Apache 2.0).
Debug builds add a `TypingTestActivity` and key-centre logging for scripted tests; `build-all.sh` checks that neither
reaches the release APK.

Build: `../build-all.sh --only keyboard`.
