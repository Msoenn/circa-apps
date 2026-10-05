# Circa apps, inherited by device/circa (common/circa.mk) when this repository is checked out at vendor/circa-apps.
# The modules exist only after ./build-all.sh has written prebuilt/ (APKs + generated Android.bp + packages.mk);
# without it the image builds without the Circa apps (Launcher3, LatinIME and DeskClock stay).
-include vendor/circa-apps/prebuilt/packages.mk
