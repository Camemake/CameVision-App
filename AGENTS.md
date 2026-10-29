# CameVision Android app

This folder is the phone app. It is not an Ego firmware tree. Do not flash boards from here.

CameVision is one app for Ego and Ego Single Sensor. English first. Simplified Chinese is `values-zh-rCN`. User-visible text stays in `strings.xml`. Disconnected title is **CameVision**. Two sensors show **CameVision Ego**. Do not guess the board in the launcher title.

PCB schematics stay private. Do not claim vendor blobs are open. Do not add an ROS pitch.

## First start

Persist language (`en` or `zh`) and role (`usb` or `ble`) in `camevision_boot`. The first screen asks for both. Setup can change both. USB role shows USB and Setup. Bluetooth role shows Record, Card, and Setup.

## USB

Computer USB stays configuration 1: Ego ADB `2207:0006`, Single UVC. The phone selects configuration 2 (`ffs.phone`, class `0xFF`, subclass `0x43`, protocol 1). Do not replace that computer configuration, and do not raise `bcdUSB` at bind.

Open the link once and hold it. Do not close and reopen USB because a frame is late or Android sends a brief detach during `setConfiguration`.

Recordings are `My Recordings/<session>/episode` and `synced`, HEVC 1920×1200 at 50 fps, both eyes and both IMUs. Live preview pauses while a recording is running. microSD is a buffer only when that switch is on. The phone writes `cam_left.mp4` and `cam_right.mp4` after the video packets have arrived. Do not finish the session on the stop reply if video packets are still in flight. `T_COMPLETE` closes the episode.

## Bluetooth

Standalone and server modes stay on the card layout. Preview and record use Bluetooth. HTTP `:8090` is the server-mode fallback.

```
<card>/camevision/sessions/<YYYYMMDDTHHMMSSZ>/
  session.json
  imu0.csv
  cam0/video.mp4
  COMPLETE
```

## Must keep working

- Find a camera by name containing `CameVision` or GATT `6c9a0001-1a6f-4e84-8a00-63616d657631`.
- Start / stop recording over Bluetooth.
- Card tab: space, session list, clear, delete, nearly-full warning.
- Send through the phone to the server in Setup. Refuse while the host still contains `server.example.com`.
- Position preview over the BLE frame characteristic.
- About reads board title, firmware, SKU, sensors, protocol.
- Server contract: `server.template.json`.

## Do not

- Bind the app to one SKU in the launcher title.
- Persist or print a Wi-Fi PSK in git.
- Treat the Single UVC webcam as an app screen.
- Replace the Bluetooth card layout with the episode tree. The episode tree is the USB session only.
