# CameVision

Android app for CameVision cameras. Version **1.2.10**. One install talks to CameVision Ego and CameVision Ego Single Sensor. The connected camera sends its own title, firmware, and sensor count. Until a board is linked, the home screen shows **CameVision**. With two sensors it shows **CameVision Ego**.

This repository is the phone app only. Device firmware lives in the Ego and Ego Single Sensor repositories. PCB schematics stay private.

English is the app language. **中文** is selectable on first start and in Setup. Every sentence on screen is in `app/src/main/res/values/strings.xml`, with Simplified Chinese in `values-zh-rCN/strings.xml`.

## First start

The first launch asks two questions. Setup can change both later.

1. **Language** — English or 中文.
2. **This phone** — USB recording and viewing, or Bluetooth control only.

USB mode shows the USB screen and Setup. Bluetooth mode shows Record, Card, and Setup.

## USB recording and viewing

The computer keeps USB configuration 1. Ego stays ADB (`2207:0006`). Ego Single Sensor stays the UVC webcam. The phone selects configuration 2, a vendor bulk link, and is the USB host.

- Live view: Left, Both, Right, and Depth. Depth is 5 fps. Pictures are rotated 180°, the same way as the live page.
- While a recording is running, live view pauses so the video stays smooth. The timer, file location, and IMU stay on screen.
- Record: HEVC 1920×1200 at 50 fps, both eyes and both IMUs.
- microSD buffer, when the switch is on: the camera keeps the session on the card, then the phone downloads it. When the switch is off, the phone writes the session itself.

Each recording is one folder:

```
My Recordings/<session>/
  episode/
    cam_left.mp4
    cam_right.mp4
    cam_left.annexb
    cam_right.annexb
    cam_left.timestamps.jsonl
    cam_right.timestamps.jsonl
    imu_left.jsonl
    imu_right.jsonl
    manifest.json
    sync_point.json
  synced/
    frame_map.jsonl
    sync_report.json
```

On this phone the folder is under internal storage:

`Android/data/eu.camemake.camevision/files/My Recordings/<session>/`

The USB screen lists the files and can copy that path. View plays the mp4 files.

## Bluetooth control

Bluetooth starts and stops a recording on the camera card. The phone can copy those files and upload them to the server in Setup.

1. **Standalone** — files stay on the card until this phone uploads them.
2. **Server** — the camera also uploads over its own Wi-Fi. The card is the buffer if the network drops.

Position preview and the QR trigger use Bluetooth. Wi-Fi is for the server upload.

Card layout:

```
<card>/camevision/sessions/<YYYYMMDDTHHMMSSZ>/
  session.json
  imu0.csv
  cam0/video.mp4
  COMPLETE
```

`imu0.csv` is `t_unix_ns,t_mono_ns,ax,ay,az,gx,gy,gz`. Accel is m/s². Gyro is deg/s.

## Server

Setup starts as `https://server.example.com/camevision/v1`. Replace it before an upload. Leave the token empty when the server is open. The contract is `server.template.json`.

```
POST /sessions
POST /sessions/{id}/files
POST /sessions/{id}/complete
```

Headers: `X-CameVision-Product`, `X-CameVision-Session`, `X-CameVision-Path`.

## Build

```
gradlew.bat assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

The versioned file for 1.2.10 is `CameVision-1.2.10.apk`. Requires Android 7 (API 24) or newer.
