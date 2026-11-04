# NuminOS

NuminOS is an Android app, not an operating system. It puts a small symbol above your other apps. Tap it and the whole screen offers a sentence to live with, your practice videos and a meditation, then folds away again.

It has no account and no server. Everything you add stays on your phone. The internet is used only for YouTube titles, thumbnails and videos, and to download the starter books from this repository's releases.

Requires Android 8.0 or newer.

## What it does

- **Sentence:** shows a random line from marked passages in the books on your shelf, your liked passages and your own sentences, or one you fix in place. Tap it to open the library, tap its title to open the book at that passage, and long-press it for more actions.
- **Library:** a bookshelf with the books you've added, a notebook for your sentences and a jar for passages you like. Books open on the whole screen with images, tables and adjustable text, and they remember your place.
- **Videos:** keeps your YouTube practice videos, in categories you create and the order you choose. It reopens on the category and place you left. One tap opens the video in SmartTube if it's installed, otherwise in YouTube.
- **Meditation:** tap Meditate to start with your last duration. Tap the duration to choose another on a wheel, or hold it, drag and let go to set it and start in one move. Choose ∞ to sit with no timer, which ends when you end it or after 2 hours. There's an opening and closing bell. During a sit the screen stays on and dimmed in front of your altar, and a tap shows the time left. You can let the screen sleep in Settings.
- **Altar:** arrange your own with the Altar button. Drag a candle, incense, a singing bowl, a gong, a lotus, a leaf, stones, your symbol, text, the timer or a PNG up from the tray. Drag to move, use two fingers to rotate or resize, and drag to the trash to remove. Touching the bowl or gong rings it, even during a sit.
- **Themes:** choose yin-yang, enso, om, dharma wheel or cross when you first open the app. Each has its own colors, and most start with a matching book.
- **Settings:** change the theme, choose light or dark (it follows your system by default), set the symbol's opacity and its offset from the edge, or delete everything and start over.

## Add your own

- **A video:** in the YouTube app, tap Share and choose NuminOS. You can also tap + in the main window and paste a link.
- **An image:** tap Image in the altar tray, or share a PNG from any app to NuminOS.
- **A sentence:** tap the sentence, open the notebook and tap +. You can also share selected text from any app to NuminOS.
- **A book:** on the shelf, tap +. You can add a starter book, an EPUB found on your phone or any file you choose. Your EPUBs open where they are, with nothing copied or converted.
- **Remove something:** long-press a video to remove it, rearrange it or change its category, or long-press one of your sentences and tap delete.

## Install

Download the APK from [Releases](https://github.com/mateotomas2/numinos/releases/latest) and open it on your phone. Choose a theme, then allow **Show over other apps**.

If Android says this is a restricted setting, open NuminOS in App info, tap the ⋮ menu and choose **Allow restricted settings**.

To list the EPUB files on your phone, NuminOS asks for **All files access**. It only opens EPUB files and doesn't upload anything, which you can check in this code. If you'd rather not allow it, **Choose a file** works without it. Only add books you have the right to use. Copy-protected (DRM) EPUBs can't be opened, so most store-bought books won't work.

If the symbol disappears after a while, set the app's battery use to **Unrestricted**. Some phone brands close background apps to save battery.

## Books

| Book | Translation | Source |
|---|---|---|
| Tao Te Ching | James Legge (1891) | [Project Gutenberg](https://www.gutenberg.org/ebooks/216) |
| Upanishads | Swami Paramananda (1919) | [Project Gutenberg](https://www.gutenberg.org/ebooks/3283) |
| Dhammapada | F. Max Müller (1881) | [Project Gutenberg](https://www.gutenberg.org/ebooks/2017) |
| Bible | World English Bible | [eBible.org](https://ebible.org/) |

`tools/make_books.py` generates these as EPUB files from the sources above, and the app downloads them from [this release](https://github.com/mateotomas2/numinos/releases/tag/books-1), checking each file's fingerprint. The script also marks a selection of passages for random sentences. To change the marks, edit the lists in that script and run it again. The Upanishads source covers the Isa, Katha and Kena, and its commentary is shown in a quieter style.

## Build

You need JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Releases

- **Pull request:** publishes a signed pre-release, replaced on every push and removed when the PR closes.
- **Merge to `main`:** publishes the final release. Raise `versionName` in `app/build.gradle.kts` in every PR, or its build fails.
- **Signing:** the pipeline reads the `SIGNING_KEYSTORE_B64` and `SIGNING_PASSWORD` repository secrets. Keep a backup of the key, because Android only installs updates signed with the same key.

## License

[GPL-3.0](LICENSE). The bell sound is generated by `tools/make_bell.py`.
