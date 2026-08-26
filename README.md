# Tuya ZG-IR01 Zigbee IR Blaster — Hubitat driver

Hubitat driver for the battery-powered Tuya Zigbee IR blaster that reports as
manufacturer `_TZE200_33rdmvgw`, model `ZG-IR01`. These are sold unbranded on
AliExpress under many names; the model string in Hubitat's device **Data**
section is the reliable way to identify one.

The device is both an IR blaster (learn and send) and a temperature / humidity
sensor.

## Why this fork exists

This is a fork of [luckygerbils/hubitat-tuya-zigbee-ir][upstream] by Sean
Anastasi, which targets the mains-powered ZS06 / TS1201. On the battery ZG-IR01
that driver can **learn** codes but cannot **send** them: the firmware requests
every data chunk with sequence `0` instead of echoing the sequence the hub
assigned, so the send aborts on the first chunk with a `NullPointerException`.
Learning is unaffected because there the device picks the sequence itself.

[upstream]: https://github.com/luckygerbils/hubitat-tuya-zigbee-ir

## Changes from upstream

1. **Sending works on the ZG-IR01** — tolerate a device that does not echo the
   hub-assigned sequence number.
2. **Abandoned send buffers are reaped by age.** Previously an interrupted
   transfer leaked its buffer, which (with 1) wedged every later send.
3. **Fingerprint for `_TZE200_33rdmvgw` / `ZG-IR01`** so it auto-selects on join
   instead of falling back to a generic `Device`.
4. **Temperature, humidity and battery** (clusters `0x0402`, `0x0405`, `0x0001`),
   previously logged as `unknown cluster ... Ignoring`.
5. **`codeList` attribute** — a readable index of learned codes.
6. **`Refresh`** — rebuilds `codeList` and reads the sensor attributes on demand.

The upstream TS1201 fingerprint is retained but untested here.

## Install

**Hubitat Package Manager** — *Install → From a URL*, then paste:

```
https://raw.githubusercontent.com/jon7sky/hubitat-zg-ir01/main/packageManifest.json
```

**Or manually** — *Drivers Code → New Driver → Import*, paste the raw URL of
`drivers/zg-ir01-ir-blaster.groovy`.

## Use

- `learn` — optionally with a name; press the button on your remote when the
  blaster's LED lights.
- `sendCode` — a learned code's name, or raw Base64.
- `mapButton` / `unmapButton` — bind a code to a button number for `push`.
- `Refresh` — refresh `codeList`, temperature, humidity and battery.

Learned codes live in `state.learnedCodes` on the device and are **destroyed if
the device is removed** — copy them out first.

### Air conditioner remotes

AC and mini-split remotes transmit their entire state (mode, setpoint, fan, vane)
in each frame, so every combination of settings is a separate learned code.
Learn the few states you actually use rather than trying to model the remote.

## Licence

GPL-3.0, inherited from upstream. See [LICENSE](LICENSE).
