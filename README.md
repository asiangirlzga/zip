# KB Compressor

Android app that losslessly compresses files and folders into a `.kbz` archive.

- Pattern mode: data that is one short pattern repeated (e.g. 1 GB of `1010...`) shrinks to a few KB
- XZ/LZMA2 (smaller) or Deflate (faster) for everything else
- Multiple files and whole folders, restore to the original
- Dark mode and before/after size comparison

Already-compressed files (MP4, JPG, ZIP) cannot shrink meaningfully; no compressor can do that.

## Get the APK
Open the **Actions** tab, choose the latest **Build APK** run, and download the `KB-Compressor-debug-apk` artifact.
Install it on your phone (allow "install unknown apps").
