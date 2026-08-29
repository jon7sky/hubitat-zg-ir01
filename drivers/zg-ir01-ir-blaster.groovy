/**
 * Tuya ZG-IR01 Zigbee IR Blaster (battery model) -- Hubitat driver
 *
 * Copyright (c) Sean Anastasi <sean@anasta.si>
 * Copyright (c) 2026 John Sevinsky
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. This program is distributed WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for details: https://www.gnu.org/licenses/
 *
 * Derived from "Tuya Zigbee IR Remote Control" by Sean Anastasi:
 *   https://github.com/luckygerbils/hubitat-tuya-zigbee-ir
 *
 * MODIFIED 2026-08-26 by John Sevinsky. Changes from the original:
 *
 *  1. Sending works on the battery ZG-IR01. That firmware asks for every data chunk
 *     with sequence 0 instead of echoing the sequence the hub assigned in the 0x00
 *     start-transmit, so the original threw NullPointerException on the first chunk
 *     and no code was ever emitted. Learning was unaffected, because there the
 *     DEVICE picks the sequence and both sides always agree. See
 *     handleCodeDataRequest.
 *  2. Abandoned send buffers are reaped by age instead of accumulating. In the
 *     original a transfer that never reached 0x04 done-sending left its buffer
 *     behind forever; combined with (1) that wedged every later send permanently.
 *  3. Fingerprint for _TZE200_33rdmvgw / ZG-IR01, so the battery unit selects this
 *     driver on join rather than falling back to a generic "Device".
 *  4. Temperature, humidity and battery. The ZG-IR01 is also a sensor and reports
 *     on clusters 0x0402, 0x0405 and 0x0001; the original logged all of it as
 *     "unknown cluster ... Ignoring".
 *  5. A codeList attribute -- a readable index of the learned codes, since
 *     state.learnedCodes is unreadable once it holds more than one entry.
 *  6. Refresh capability: rebuilds codeList and reads the sensor attributes on
 *     demand, rather than waiting for the device's own battery report.
 *
 * The mains TS1201 fingerprint from the original is retained and untested here.
 */

/**
 * Protocol notes, from the original driver (written for the mains ZS06 / TS1201;
 * the battery ZG-IR01 speaks the same ED00/E004 protocol).
 *
 * This driver is based largely on the work already done to integrate this device with Zigbee2MQTT, aka zigbee-herdsman
 * https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/zosung.ts
 * https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zcl/definition/cluster.ts#L5260-L5359
 *
 * Zigbee command payloads for the ZS06 seem to be largely hex encoded structs.
 * In this driver, this mapping is handled by the toPayload and toStruct functions which convert a Map of
 * struct data into a hex byte string according to a given struct layout definition.
 *
 * The learn and sendCode commands consist of a back-and-forth sequence of command messages between
 * the hub and the device. The names for these messages are not official and just guesses. 
 * Here's an outline of the flow:
 *
 * learn sequence:
 *  1. hub sends 0xe004 0x00 (learn) with the JSON {"study":0} (as an ASCII hex byte string)
 *  2. device led illuminates, user sends IR code to the device using original remote
 *  3. device sends 0xed00 0x00 (start transmit) with a sequence value it generates + the code length 
 *     - All subsequent messages generally include this same sequence value
 *  4. hub sends 0xed00 0x01 (start transmit ack)
 *  5. device sends 0xed00 0x0B (ACK) with 0x01 as the command being acked
 *  6. hub sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  7. device sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (5) and (6) until the received data length matches the length given in (3)]
 *  8. hub sends 0xed00 0x04 (done sending)
 *  9. device sends 0xed00 0x05 (done receiving)
 *  10. hub sets "lastLearnedCode" (base64 value), 
 *      clears data associated with this sequence, 
 *      and sends 0xe004 0x00 (learn) with the JSON {"study":1}
 *  11. device led turns off
 *
 * sendCode sequence:
 *  1. hub sends 0xed00 0x00 (start transmit) with a generated sequence value + the code length
 *     - All subsequent messages generally include this same sequence value
 *  2. device sends 0xed00 0x01 (start transmit ack)
 *     - We ignore this
 *  3. device sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  4. hub sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (3) and (4) until the device sends 0xed00 0x04 (done sendng)]
 *  5. device sends 0xed00 0x04 (done sending)
 *  6. hub sends 0xed00 0x05 (done receiving), 
 *     clears data associated with this sequence
 *  7. device emits the IR code
 *
 * There are also various other "ACK" messages sent after each command.
 * In general, we do nothing in response to these (and the device doesn't appear to require we
 * send them in response to its messages).
 */

import groovy.transform.Field

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import java.util.concurrent.ConcurrentHashMap

// These BEGIN and END comments are so this section can be snipped out in unit tests.
// I'm not sure what's necessary to make this syntax work in standard Groovy
// BEGIN METADATA
metadata {
    definition (name: "Tuya ZG-IR01 Zigbee IR Blaster (Battery)",
                namespace: "jon7sky",
                author: "John Sevinsky",
                importUrl: "https://raw.githubusercontent.com/jon7sky/hubitat-zg-ir01/main/drivers/zg-ir01-ir-blaster.groovy") {
        capability "PushableButton"
        capability "Refresh"
        // Only the battery ZG-IR01 reports these. The mains TS1201 never will, so on
        // that device the attributes stay empty -- and it will still show up in
        // "pick a temperature sensor" lists. Kept in one driver anyway: the IR
        // transfer protocol below is the hard part and must not be maintained twice.
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"

        command "learn", [
            [name: "Code Name", type: "STRING", description: "Name for learned code (optional)"]
        ]
        command "sendCode", [
            [name: "Code*", type: "STRING", description: "Name of learned code or raw Base64 bytes of code to send"]
        ]
        command "addCode", [
            [name: "Code Name*", type: "STRING", description: "Name to store this code under"],
            [name: "Base64 Code*", type: "STRING", description: "Raw Base64 IR code (Broadlink format)"]
        ]
        command "forgetCode", [
            [name: "Code Name*", type: "STRING", description: "Name of learned code to forget"]
        ]
        command "forgetAllCodes"
        command "mapButton", [
            [name: "Button*", type: "NUMBER", description: "Button number to map"],
            [name: "Code Name*", type: "STRING", description: "Name of learned code to map to the given button"]
        ]
        command "unmapButton", [
            [name: "Button*", type: "NUMBER", description: "Button number to unmap"]
        ]
       
        attribute "lastLearnedCode", "STRING"
        // Readable index of state.learnedCodes. Refreshed automatically whenever the
        // set of codes or button mappings changes, so it never goes stale.
        attribute "codeList", "STRING"
        // Outcome of the most recent sendCode. Exists because the Zigbee transfer is
        // only visible in the live log, which cannot be read back later or remotely --
        // if a send stalls, this attribute is left reading "sending ..." and says so.
        attribute "lastSendStatus", "STRING"
        
        // Note, my case says ZS06, but this is what Device Get Info tells me the fingerprint is
        fingerprint profileId: "0104", inClusters: "0000,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_7v1k4vufotpowp9z", model: "TS1201", deviceJoinName: "Tuya Zigbee IR Remote Control"
        // Battery-powered variant: a different product, not just different firmware.
        // Tuya MCU based (_TZE200_ prefix, hence the EF00 datapoint cluster) and it also
        // carries temperature (0402), humidity (0405) and power (0001), none of which the
        // mains TS1201 above reports. Without this line it joins as a generic "Device"
        // and the driver has to be selected by hand. NOTE: this firmware does not echo
        // the hub-assigned sequence when sending -- see handleCodeDataRequest.
        fingerprint profileId: "0104", inClusters: "0000,0003,0004,0005,EF00,E004,ED00,0006,0402,0405,0001", outClusters: "0003", manufacturer: "_TZE200_33rdmvgw", model: "ZG-IR01", deviceJoinName: "Tuya ZG-IR01 Zigbee IR Blaster (Battery)"
    }

    preferences {
      input name: "logLevel", type: "enum", title: "Log Level", description: "Override logging level. Default is INFO.<br>DEBUG level will reset to INFO after 30 minutes", options: ["DEBUG","INFO","WARN","ERROR"], required: true, defaultValue: "INFO"
   }
}
// END METADATA

/* 
 * Semi-persistent data
 * We don't need this permanently in state, but we do need it between message executions so just @Field doesn't work
 */
/* deviceId -> seq -> { buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> SEND_BUFFERS = new ConcurrentHashMap()
def sendBuffers() { return SEND_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> seq -> { expectedBufferLength: int, buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> RECEIVE_BUFFERS = new ConcurrentHashMap()
def receiveBuffers() { return RECEIVE_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> Stack<string|null> */
@Field static final Map<String, List<Integer>> PENDING_LEARN_CODE_NAMES = new ConcurrentHashMap()
def pendingLearnCodeNames() { return PENDING_LEARN_CODE_NAMES.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }
/* deviceId -> Stack<seq> */
@Field static final Map<String, List<Integer>> PENDING_RECEIVE_SEQS = new ConcurrentHashMap()
def pendingReceiveSeqs() { return PENDING_RECEIVE_SEQS.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }

/*********
 * ACTIONS
 */

def installed() {
    info "installed()"
    updateNumberOfButtons()
}

def updated() {
    info "updated()"
    switch (logLevel) {
    case "DEBUG": 
        debug "log level is DEBUG. Will reset to INFO after 30 minutes"
        runIn(1800, "resetLogLevel")
        break;
    case "INFO": info "log level is INFO"; break;
    case "WARN": warn "log level is WARN"; break;
    case "ERROR": error "log level is ERROR"; break;
    default: error "Unexpected logLevel: ${logLevel}"
    }
}

def configure() {
    info "configure()"
}

/**
 * Ask for the sensor attributes now rather than waiting for the device to report.
 * Battery in particular reports rarely -- temperature and humidity arrive every few
 * seconds, battery can be an hour or more apart.
 *
 * Only the clusters the device actually advertised at join are read. The mains
 * TS1201 has none of these (its inClusters are 0000,0004,0005,0003,ED00,E004,0006),
 * and asking it would just draw UNSUPPORTED_CLUSTER responses.
 */
def refresh() {
    info "refresh()"

    // Rebuild the readable code index. Hub-side only, sends nothing over Zigbee, and
    // deliberately ahead of the sensor-cluster check below so it still runs on a
    // device that has no sensors (the mains TS1201 has codes but no sensor clusters).
    listCodes()
    updateNumberOfButtons()

    final List ALL_SENSORS = [
        [POWER_CLUSTER,       0x0021, "battery",     "0001"],
        [TEMPERATURE_CLUSTER, 0x0000, "temperature", "0402"],
        [HUMIDITY_CLUSTER,    0x0000, "humidity",    "0405"],
    ]

    final List clusters = (device.getDataValue("inClusters") ?: "")
        .toUpperCase().split(",").collect { it.trim() }.findAll { it }

    final List wanted
    if (clusters.isEmpty()) {
        // Hubitat did not record a join-time cluster list for this device -- seen on
        // a device that joined by fingerprint match. Ask for everything rather than
        // silently refreshing nothing; an UNSUPPORTED_CLUSTER reply from a device
        // without sensors is harmless.
        debug "inClusters not recorded; reading all sensor attributes"
        wanted = ALL_SENSORS
    } else {
        wanted = ALL_SENSORS.findAll { clusters.contains(it[3]) }
    }

    if (wanted.isEmpty()) {
        info "no sensor clusters on this device (inClusters: ${clusters.join(',')}); nothing to refresh"
        return
    }

    wanted.each { w ->
        final def cmd = readAttribute(w[0], w[1])
        debug "sending (read ${w[2]}): ${cmd}"
        doSendHubCommand(cmd)
    }
}

def learn(final String optionalCodeName) {
    info "learn(${optionalCodeName})"
    pendingLearnCodeNames().push(optionalCodeName)
    sendLearn(true)
}

def sendCode(final String codeNameOrBase64CodeInput) {
    info "sendCode(${codeNameOrBase64CodeInput})"

    String learnedCode = null
    if (state.learnedCodes != null) {
        learnedCode = state.learnedCodes[codeNameOrBase64CodeInput]
    }
    
    final String base64Code
    if (learnedCode != null) {
        base64Code = learnedCode
    } else {
        // Remove all whitespace since we added newlines to the lastLearnedCode attribute + the hubitat HTML might add extra spaces
        base64Code = codeNameOrBase64CodeInput.replaceAll("\\s", "")
    }
    
    // JSON format copied from zigbee-herdsman-converters
    // Unclear if any of this can be tweaked to get different behavior
    final String jsonToSend = "{\"key_num\":1,\"delay\":300,\"key1\":{\"num\":1,\"freq\":38000,\"type\":1,\"key_code\":\"${base64Code}\"}}"
    debug "JSON to send: ${jsonToSend}"

    // Reap buffers from transfers that never reached 0x04 done-sending. Without this
    // they accumulate forever. Only genuinely stale ones are dropped -- a transfer
    // takes ~2s, so anything older than a minute is dead. Buffers are NOT cleared
    // wholesale: two sends fired back-to-back by a rule are legitimately in flight
    // together, keyed by their own seq, and must not clobber each other.
    final long nowMs = now()
    sendBuffers().keySet().toList().each { k ->
        final def v = sendBuffers()[k]
        if (v?.created != null && (nowMs - v.created) > 60000) {
            warn "Discarding abandoned send buffer seq ${k}"
            sendBuffers().remove(k)
        }
    }

    def seq = nextSeq()
    sendBuffers()[seq] = [
        buffer: jsonToSend.bytes as List,
        created: nowMs,
        codeName: codeNameOrBase64CodeInput,
        chunks: 0
    ]
    doSendEvent(name: "lastSendStatus",
                value: "sending ${codeNameOrBase64CodeInput} (${jsonToSend.bytes.length} bytes, seq ${seq})".toString())
    sendStartTransmit(seq, jsonToSend.bytes.length)
}

/**
 * Store a code under a name without learning it from a remote.
 *
 * Needed for protocols the blaster cannot capture, and for codes generated from a
 * protocol specification rather than recorded -- an air conditioner has one frame per
 * combination of settings, so producing them beats learning dozens by hand.
 *
 * The payload is Broadlink format: 0x26, repeat count, uint16 LE length, then timing
 * values. Only the leading byte is checked; a wrong code is the caller's problem, but
 * pasting something that is not an IR code at all is worth catching here.
 */
def addCode(final String codeName, final String base64Code) {
    info "addCode(${codeName})"
    final String clean = base64Code.replaceAll("\\s", "")

    // Store FIRST, validate after. An earlier version decoded the Base64 before
    // storing and returned on any failure -- so a decode the sandbox would not permit
    // silently prevented the code being saved at all. Validation is advisory: it can
    // warn about a suspect payload, but it must never be the reason a code is lost.
    final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
    learnedCodes[codeName] = clean
    info "addCode(${codeName}): stored ${clean.length()} characters"

    try {
        final byte[] raw = decodeBase64(clean)
        if (raw == null || raw.length < 8) {
            warn "addCode(${codeName}): decodes to only ${raw == null ? 0 : raw.length} bytes -- suspiciously short"
        } else if ((raw[0] & 0xFF) != 0x26) {
            warn "addCode(${codeName}): leading byte is 0x${Integer.toHexString(raw[0] & 0xFF)}, expected 0x26 for an IR code"
        } else {
            info "addCode(${codeName}): ${raw.length} bytes, header looks like an IR code"
        }
    } catch (ex) {
        debug "addCode(${codeName}): could not decode for validation (${ex}) -- stored regardless"
    }

    listCodes()
}

def forgetCode(final String codeName) {
    info "forgetCode(${codeName})"
    if (state.learnedCodes == null) {
        return
    }
    state.learnedCodes.remove(codeName)
    listCodes()
}

/**
 * Remove every learned code, and the button mappings with them.
 *
 * The mappings go too because they hold names, not codes: a mapping left pointing at
 * a deleted name would send that name to sendCode, which falls back to treating an
 * unknown name as raw Base64 and would put nonsense on the wire. Clearing both keeps
 * push() honest.
 *
 * There is no confirmation step -- Hubitat commands fire immediately -- so this logs
 * at warn level with the counts it destroyed.
 */
def forgetAllCodes() {
    final int codes = (state.learnedCodes ?: [:]).size()
    final int maps  = (state.mappedButtons ?: [:]).size()
    state.remove("learnedCodes")
    state.remove("mappedButtons")
    warn "forgetAllCodes: removed ${codes} code(s) and ${maps} button mapping(s)"
    listCodes()
    updateNumberOfButtons()
}

def mapButton(final BigDecimal button, final String codeName) {
    info "mappButton(${button}, ${codeName})"
    final Map mappedButtons = state.computeIfAbsent("mappedButtons", {k -> new HashMap()})
    mappedButtons[button.toString()] = codeName
    listCodes()
    updateNumberOfButtons()
}

/**
 * PushableButton requires a numberOfButtons attribute, and dashboard and app pickers
 * use it to decide which button numbers to offer -- leaving it unset makes the device
 * awkward or impossible to select in some of them.
 *
 * There is no physical button count here, so report the highest mapped number: map
 * button 3 and the UI offers 1..3. Numbers below it that are unmapped simply warn
 * when pushed, which is the same as before.
 */
def updateNumberOfButtons() {
    final Map mapped = state.mappedButtons ?: [:]
    int highest = 0
    mapped.keySet().each { k ->
        try {
            final int n = Integer.parseInt(k.toString())
            if (n > highest) { highest = n }
        } catch (ignored) { /* non-numeric key, skip */ }
    }
    doSendEvent(name: "numberOfButtons", value: highest,
                descriptionText: "${device} has ${highest} mapped button(s)".toString())
}

def unmapButton(final BigDecimal button) {
    info "unmapButton(${button})"
    if (state.mappedButtons == null) {
        return
    }
    state.mappedButtons.remove(button.toString())
    listCodes()
    updateNumberOfButtons()
}

/**
 * Rebuild the codeList attribute. Not a command any more -- it is called from
 * refresh() and from every path that changes the code set (learn completion,
 * forgetCode, mapButton, unmapButton), so the attribute cannot go stale.
 *
 * One line per learned code, name padded so the
 * button column lines up, with the mapped button number if there is one.
 * state.learnedCodes is a raw map of name -> base64 blob and is unreadable in the
 * State Variables panel once there is more than one entry.
 */
def listCodes() {
    final Map codes = state.learnedCodes ?: [:]
    final Map mapped = state.mappedButtons ?: [:]

    if (codes.isEmpty()) {
        info "No codes learned yet"
        doSendEvent(name: "codeList", value: "(no codes learned)",
                    descriptionText: "${device} has no learned codes".toString())
        return
    }

    // name -> [button numbers], inverted from state.mappedButtons
    final Map byName = [:]
    mapped.each { btn, nm ->
        if (byName[nm] == null) { byName[nm] = [] }
        byName[nm].add(btn.toString())
    }

    final List names = codes.keySet().toList().sort { it.toLowerCase() }
    final int width = names.collect { it.length() }.max()

    // The attribute is rendered as HTML by the device page, which collapses newlines
    // AND runs of spaces -- so it needs <br> between entries and &nbsp; for padding,
    // or every code lands on one squashed line.
    final List htmlLines = []
    names.each { nm ->
        final def btns = byName[nm]
        final String suffix = btns ? "button ${btns.sort().join(', ')}" : "(unmapped)"
        htmlLines.add("${nm}${'&nbsp;' * (width - nm.length() + 3)}${suffix}")
    }

    // The log gets a compact single line: the log viewer stops at the first newline,
    // so a multi-line message shows only its header, and column padding is pointless
    // there anyway.
    final List logParts = names.collect { nm ->
        final def btns = byName[nm]
        return btns ? "${nm} (button ${btns.sort().join(', ')})" : nm
    }
    info "${codes.size()} learned code(s): ${logParts.join(', ')}"
    doSendEvent(name: "codeList", value: htmlLines.join("<br>"),
                descriptionText: "${device} has ${codes.size()} learned code(s)".toString())
}

def push(final BigDecimal button) {
    info "push(${button})"
    if (state.mappedButtons == null) {
        return
    }
    final String codeName = state.mappedButtons[button.toString()]
    if (codeName == null) {
        warn "Unmapped button ${button}"
        return
    }
    sendCode(codeName)
    // PushableButton contract: report the press. Emitted only once a mapped code is
    // actually sent -- a push at an unmapped number did nothing, and saying otherwise
    // would fire rules for an action that never happened. isStateChange because
    // pressing the same button twice is two events, not one.
    doSendEvent(name: "pushed", value: button.intValue(), isStateChange: true,
                descriptionText: "${device} button ${button} pushed (${codeName})".toString())
}

/*********
 * MESSAGES
 */

def parse(final String description) {
    final def descMap = zigbee.parseDescriptionAsMap(description)
     
    switch (descMap.clusterInt) {
    case LEARN_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case LEARN_CLUSTER_LEARN:
            debug "received ${LEARN_CLUSTER_LEARN} (learn): ${descMap.data}"
            break
        case LEARN_CLUSTER_ACK:
            debug "received ${LEARN_CLUSTER_ACK} (learn ack): ${descMap.data}"
            break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
        }
        break
    case TRANSMIT_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case TRANSMIT_CLUSTER_START_TRANSMIT:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT} (start transmit): ${descMap.data}"
            handleStartTransmit(parseStartTransmit(descMap.data))
            break
        case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT_ACK} (start transmit ack): ${descMap.data}"
            // I think this is just an ACK of the recieved initial msg 0
            // There's nothing do to here
            break
        case TRANSMIT_CLUSTER_CODE_DATA_REQUEST:
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_REQUEST} (code data request): ${descMap.data}"
            handleCodeDataRequest(parseCodeDataRequest(descMap.data))
            break
        case TRANSMIT_CLUSTER_CODE_DATA_RESPONSE: 
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_RESPONSE} (code data response):: ${descMap.data}"
            handleCodeDataResponse(parseCodeDataResponse(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_SENDING: 
            debug "received ${TRANSMIT_CLUSTER_DONE_SENDING} (done sending):: ${descMap.data}"
            handleDoneSending(parseDoneSending(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_RECEIVING: 
            debug "received ${TRANSMIT_CLUSTER_DONE_RECEIVING} (done receiving): ${descMap.data}"
            handleDoneReceiving(parseDoneReceiving(descMap.data))
            break
        case TRANSMIT_CLUSTER_ACK:
            debug "received ${TRANSMIT_CLUSTER_ACK} (ack): ${descMap.data}"
            handleAck(parseAck(descMap.data))
            break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
        }
        break
    case TEMPERATURE_CLUSTER:
        handleTemperature(descMap)
        break
    case HUMIDITY_CLUSTER:
        handleHumidity(descMap)
        break
    case POWER_CLUSTER:
        handleBattery(descMap)
        break
    case TUYA_CLUSTER:
        // The ZG-IR01 streams Tuya datapoints constantly. Temperature, humidity and
        // battery all arrive on the standard clusters above, so nothing here is
        // needed -- logged at debug so it stops flooding the log with warnings.
        debug "Tuya datapoint report (ignored): ${descMap.data}"
        break
    default:
        warn "received unknown message from unknown cluster: 0x${descMap.command} (cluster 0x${Integer.toHexString(descMap.clusterInt)}). Ignoring"
        debug "descMap = ${descMap}"
        break
    }
}

/*
 * Sensor clusters -- battery ZG-IR01 only
 */
@Field static final int POWER_CLUSTER       = 0x0001
@Field static final int TEMPERATURE_CLUSTER = 0x0402
@Field static final int HUMIDITY_CLUSTER    = 0x0405
@Field static final int TUYA_CLUSTER        = 0xEF00

/** Signed 16-bit, hundredths of a degree C. */
def handleTemperature(final Map descMap) {
    if (descMap.attrInt != 0x0000 || descMap.value == null) {
        debug "temperature: ignoring attr ${descMap.attrInt}"
        return
    }
    int raw = Integer.parseInt(descMap.value, 16)
    if (raw > 0x7FFF) { raw -= 0x10000 }   // two's complement; below-freezing readings
    final double celsius = raw / 100.0d
    final String scale = location.temperatureScale
    double t = (scale == "F") ? (celsius * 9.0d / 5.0d + 32.0d) : celsius
    t = Math.round(t * 10.0d) / 10.0d
    doSendEvent(name: "temperature", value: t, unit: "°${scale}",
                descriptionText: "${device} temperature is ${t}°${scale}".toString())
}

/** Unsigned 16-bit, hundredths of a percent. */
def handleHumidity(final Map descMap) {
    if (descMap.attrInt != 0x0000 || descMap.value == null) {
        debug "humidity: ignoring attr ${descMap.attrInt}"
        return
    }
    final int raw = Integer.parseInt(descMap.value, 16)
    final double h = Math.round((raw / 100.0d) * 10.0d) / 10.0d
    doSendEvent(name: "humidity", value: h, unit: "%",
                descriptionText: "${device} humidity is ${h}%".toString())
}

/** 0x0021 BatteryPercentageRemaining, in HALF percent units. May ride in additionalAttrs. */
def handleBattery(final Map descMap) {
    final List attrs = [[attrInt: descMap.attrInt, value: descMap.value]]
    if (descMap.additionalAttrs) { attrs.addAll(descMap.additionalAttrs) }
    attrs.each { a ->
        if (a.attrInt == 0x0021 && a.value != null) {
            int pct = (int) Math.round(Integer.parseInt(a.value, 16) / 2.0d)
            pct = Math.max(0, Math.min(100, pct))
            doSendEvent(name: "battery", value: pct, unit: "%",
                        descriptionText: "${device} battery is ${pct}%".toString())
        } else if (a.attrInt == 0x0020 && a.value != null) {
            debug "battery voltage: ${Integer.parseInt(a.value, 16) / 10.0d}V"
        }
    }
}

/*
 * Learn command cluster
 */
@Field static final int LEARN_CLUSTER = 0xe004

/**
 * 0x00 Learn
 */
@Field static final int LEARN_CLUSTER_LEARN = 0x00

String newLearnMessage(final boolean learn) {
    return command(
        LEARN_CLUSTER, 
        LEARN_CLUSTER_LEARN, 
        toPayload("{\"study\":${learn ? 0 : 1}}".bytes)
    )
}

def sendLearn(final boolean learn) {
    final def cmd = newLearnMessage(learn)
    debug "sending (learn(${learn})): ${cmd}"
    doSendHubCommand(cmd)
}

/**
 * 0x0B ACK
 */
@Field static final int LEARN_CLUSTER_ACK = 0x0B

/*
 * Transmit command cluster
 */

@Field static final int TRANSMIT_CLUSTER = 0xed00

/**
 * 0x0B ACK
 */
@Field static final int TRANSMIT_CLUSTER_ACK = 0x0B
@Field static final def ACK_PAYLOAD_FORMAT = [
    [ name: "cmd",    type: "uint16" ],
]

Map parseAck(final List<String> payload) {
    return toStruct(ACK_PAYLOAD_FORMAT, payload)
}

String newAckMessage(final int cmd) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_ACK, 
        toPayload(ACK_PAYLOAD_FORMAT, [ cmd: cmd ])
    )
}

def handleAck(final Map message) {
    switch (message.cmd) {
    case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
        // This is the only ack we care about
        // zigbee-herdsman-converters seems to handle this by just delaying this by a fixed time after
        // sending 0x00, but I think this is better
        sendCodeDataRequest(pendingReceiveSeqs().pop(), 0)
        break
    }
}

/**
 * 0x00 Start Transmit
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT = 0x00
@Field static final def START_TRANSMIT_PAYLOAD_FORMAT = [
    [ name: "seq",    type: "uint16" ],
    [ name: "length", type: "uint32" ],
    [ name: "unk1",   type: "uint32" ], 
    [ name: "unk2",   type: "uint16" ], // Cluster Id?
    [ name: "unk3",   type: "uint8" ], 
    [ name: "cmd",    type: "uint8" ], 
    [ name: "unk4",   type: "uint16" ],
]

def newStartTransmitMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_START_TRANSMIT,
        toPayload(
            START_TRANSMIT_PAYLOAD_FORMAT, 
            [
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

def sendStartTransmit(final int seq, final int length) {
    final def cmd = newStartTransmitMessage(seq, length)
    debug "sending (start transmit): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmit(final List<String> payload) {
    return toStruct(START_TRANSMIT_PAYLOAD_FORMAT, payload)
}

def handleStartTransmit(final Map message) {
    pendingReceiveSeqs().push(message.seq)
    receiveBuffers()[message.seq] = [
        expectedBufferLength: message.length,
        buffer: []
    ]
    sendStartTransmitAck(message)
}

/**
 * 0x01 Start Transmit ACK 
 * ??? I don't actually know what this is for, but it needs to happen before 0x02.
 * The body seems to just be the same as 0x00 with an extra zero byte at the beginning
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT_ACK = 0x01
@Field static final def START_TRANSMIT_ACK_PAYLOAD_FORMAT = [
    [ name: "zero",   type: "uint8" ],
    [ name: "seq",    type: "uint16" ],
    [ name: "length", type: "uint32" ],
    [ name: "unk1",   type: "uint32" ], 
    [ name: "unk2",   type: "uint16" ], // Cluster Id?
    [ name: "unk3",   type: "uint8" ], 
    [ name: "cmd",    type: "uint8" ], 
    [ name: "unk4",   type: "uint16" ],
]

String newStartTransmitAckMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_START_TRANSMIT_ACK, 
        toPayload(
            START_TRANSMIT_ACK_PAYLOAD_FORMAT, 
            [
                zero: 0,
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

void sendStartTransmitAck(final Map message) {
    final def cmd = newStartTransmitAckMessage(message.seq, message.length)
    debug "sending (start transmit ack): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmitAck(final List<String> payload) {
    return toStruct(START_TRANSMIT_ACK_PAYLOAD_FORMAT, payload)
}

/**
 * 0x02 Code Data Request
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_REQUEST = 0x02
@Field static final def CODE_DATA_REQUEST_PAYLOAD_FORMAT = [
    [ name: "seq",      type: "uint16" ],
    [ name: "position", type: "uint32" ],
    [ name: "maxlen",   type: "uint8" ],
]

String newCodeDataRequestMessage(final int seq, final int position) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_CODE_DATA_REQUEST, 
        toPayload(
            CODE_DATA_REQUEST_PAYLOAD_FORMAT, 
            [
                seq: seq,
                position: position,
                maxlen: 0x38, // Limits? Unknown, this default copied from zigbee-herdsman-converters
            ]
        )
    )
}

void sendCodeDataRequest(final int seq, final int position) {
    final def cmd = newCodeDataRequestMessage(seq, position)
    debug "sending (code data request): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataRequest(final List<String> payload) {
    return toStruct(CODE_DATA_REQUEST_PAYLOAD_FORMAT, payload)
}

def handleCodeDataRequest(final Map message) {
    final int position = message.position

    // Some TS1201 firmware (seen on the battery-powered blaster) does not echo the
    // sequence the hub assigned in 0x00 start-transmit; it requests data with seq 0
    // even though it echoed the real seq in the 0x01 ack. Fall back to the single
    // in-flight send when the lookup misses. On firmware that does echo correctly
    // the lookup succeeds and this never runs.
    Map seqData = sendBuffers()[message.seq]
    if (seqData == null) {
        // Attribute the request to the most recently started transfer. Requiring
        // exactly one in flight is not good enough: a single abandoned buffer would
        // then wedge every later send until it aged out.
        final def entries = sendBuffers().entrySet().toList()
        if (entries.isEmpty()) {
            log.error "Unexpected seq: ${message.seq} and no send in flight"
            return
        }
        final def best = entries.max { it.value.created ?: 0 }
        seqData = best.value
        // Every chunk of every transfer hits this on such firmware, so say it once
        // per transfer rather than once per chunk.
        if (!seqData.warnedSeqMismatch) {
            seqData.warnedSeqMismatch = true
            warn "Device requested seq ${message.seq}, falling back to in-flight seq ${best.key} (further chunks silent)"
        }
    }
    seqData.chunks = (seqData.chunks ?: 0) + 1
    final List<Byte> buffer = seqData.buffer
    // Apparently 55 bytes at a time. TODO: experiment, should this be maxlen bytes?
    final byte[] part = buffer.subList(position, Math.min(position + 55, buffer.size())) as byte[]
    final int crc = checksum(part)

    sendCodeDataResponse(
        message.seq,
        position,
        part,
        crc
    )
}

/**
 * 0x03 Code Data Respoonse
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_RESPONSE = 0x03
@Field static final def CODE_DATA_RESPONSE_PAYLOAD_FORMAT = [
    [ name: "zero",       type: "uint8" ],
    [ name: "seq",        type: "uint16" ],
    [ name: "position",   type: "uint32" ],
    [ name: "msgpart",    type: 'octetStr' ],
    [ name: "msgpartcrc", type: "uint8"],
]

String newCodeDataResponseMessage(final int seq, final int position, final byte[] data, final int crc) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_CODE_DATA_RESPONSE,
        toPayload(
            CODE_DATA_RESPONSE_PAYLOAD_FORMAT,
            [
                zero: 0,
                seq: seq,
                position: position,
                msgpart: data,
                msgpartcrc: crc
            ]
        )
    )
}

void sendCodeDataResponse(final int seq, final int position, final byte[] data, final int crc) {
    final def cmd = newCodeDataResponseMessage(seq, position, data, crc)
    debug "sending (code data response, position: ${position}) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataResponse(final List<String> payload) {
    return toStruct(CODE_DATA_RESPONSE_PAYLOAD_FORMAT, payload)
}

def handleCodeDataResponse(final Map message) {
    final Map seqData = receiveBuffers()[message.seq]
    if (seqData == null) {
        log.error "Unexpected seq: ${message.seq}"
        return
    }

    final List<Byte> buffer = seqData.buffer

    final int position = message.position
    if (position != buffer.size) {
        log.error "Position mismatch! expected: ${buffer.size} was: ${position}"
        return
    }

    final int actualCrc = checksum(message.msgpart)
    final int expectedCrc = message.msgpartcrc
    if (actualCrc != expectedCrc) {
        log.error "CRC mismatch! expected: ${expectedCrc} was: ${actualCrc}"
        return
    }

    buffer.addAll(message.msgpart)

    if (buffer.size < seqData.expectedBufferLength) {
        sendCodeDataRequest(message.seq, buffer.size)
    } else {
        sendDoneSending(message.seq)
    }   
}

/**
 * 0x04 Done Sending
 */
@Field static final int TRANSMIT_CLUSTER_DONE_SENDING = 0x04
@Field static final def DONE_SENDING_PAYLOAD_FORMAT = [
    [ name: "zero1", type: "uint8" ],
    [ name: "seq",   type: "uint16" ],
    [ name: "zero2", type: "uint16" ],
]

String newDoneSendingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_DONE_SENDING, 
        toPayload(
            DONE_SENDING_PAYLOAD_FORMAT,
            [
                zero1: 0,
                seq: seq,
                zero2: 0
            ]
        )
    )
}

def sendDoneSending(final int seq) {
    final def cmd = newDoneSendingMessage(seq)
    debug "sending (done sending) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneSending(final List<String> payload) {
    return toStruct(DONE_SENDING_PAYLOAD_FORMAT, payload)
}

def handleDoneSending(final Map message) {
    info "code fully sent"
    final Map finished = sendBuffers()[message.seq] ?:
        (sendBuffers().isEmpty() ? null : sendBuffers().entrySet().max { it.value.created ?: 0 }.value)
    doSendEvent(name: "lastSendStatus",
                value: "sent ${finished?.codeName} (${finished?.buffer?.size()} bytes in ${finished?.chunks} chunks)".toString())
    // Same seq caveat as handleCodeDataRequest: if the device finished with a seq we
    // never issued, drop the single in-flight buffer instead. Without this the buffer
    // leaks and the NEXT send sees two entries, which defeats the fallback above.
    if (sendBuffers().remove(message.seq) == null) {
        final def entries = sendBuffers().entrySet().toList()
        if (!entries.isEmpty()) {
            sendBuffers().remove(entries.max { it.value.created ?: 0 }.key)
        }
    }
    sendDoneReceiving(message.seq) 
}

/**
 * 0x05 Done Receiving
 */
@Field static final int TRANSMIT_CLUSTER_DONE_RECEIVING = 0x05
@Field static final def DONE_RECEIVING_PAYLOAD_FORMAT = [
    [ name: "seq",        type: "uint16" ],
    [ name: "zero",       type: "uint16" ],
]

String newDoneReceivingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER, 
        TRANSMIT_CLUSTER_DONE_RECEIVING, 
        toPayload(
            DONE_RECEIVING_PAYLOAD_FORMAT,
            [
                seq: seq,
                zero: 0
            ]
        )
    )
}

def sendDoneReceiving(final int seq) {
    final def cmd = newDoneReceivingMessage(seq)
    debug "sending (done receiving): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneReceiving(final List<String> payload) {
    return toStruct(DONE_RECEIVING_PAYLOAD_FORMAT, payload)
}

def handleDoneReceiving(final Map message) {
    final Map seqData = receiveBuffers().remove(message.seq)
    final String code = encodeBase64(seqData.buffer.toArray() as byte[])
    info "learned code: ${code}"
    checkCapture(seqData.buffer)

    // Add a newline every 25 characters so it wraps on the Hubitat UI
    // Otherwise the code overflows the page, making it hard to copy
    // We remove all whitespace in sendCode to undo this
    final String eventValue = code.split("(?<=\\G.{25})").join("\n")
    doSendEvent(name: "lastLearnedCode", value: eventValue, descriptionText: "${device} lastLearnedCode is ${code}".toString())

    final String optionalCodeName = pendingLearnCodeNames().pop()
    if (optionalCodeName != null) {
        final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
        learnedCodes[optionalCodeName] = code
    }

    listCodes()
    sendLearn(false)
}


/**
 * Warn when a freshly learned capture contains no actual IR signal.
 *
 * The blaster writes 0xFFFF -- the largest value a 16-bit timing field holds --
 * whenever it waits for an edge and never sees one. A capture where every timing is
 * that value means the remote was never detected: aimed away, out of range, pressed
 * outside the learn window, or a flat remote battery. Such a code stores and later
 * transmits perfectly happily, so without this check the failure does not surface
 * until the target device fails to react, long after the cause is forgotten.
 *
 * Payload is Broadlink style: a 4-byte header (type, repeat count, uint16 LE
 * length) then timing values, each either one byte or 0x00 followed by a
 * big-endian uint16 for gaps too long to fit in a byte.
 */
def checkCapture(final List buffer) {
    if (buffer == null || buffer.size() < 8) {
        warn "Learned code is only ${buffer == null ? 0 : buffer.size()} bytes -- capture looks empty"
        return
    }

    final List ticks = []
    int i = 4
    while (i < buffer.size()) {
        final int b = ((buffer[i] as int) & 0xFF)
        if (b == 0 && (i + 2) < buffer.size()) {
            ticks.add(((((buffer[i + 1] as int) & 0xFF) << 8) | ((buffer[i + 2] as int) & 0xFF)))
            i += 3
        } else {
            ticks.add(b)
            i += 1
        }
    }

    if (ticks.isEmpty()) {
        warn "Learned code contains no timing data -- capture looks empty"
        return
    }

    final int saturated = ticks.count { it == 0xFFFF }
    final int distinct = new HashSet(ticks).size()

    if (distinct <= 1 || saturated > (ticks.size() / 2)) {
        warn "Capture looks EMPTY: ${saturated}/${ticks.size()} timings are 0xFFFF and only " +
             "${distinct} distinct value(s). The remote was probably not detected -- aim it at " +
             "the blaster from close range and press the button while the LED is lit, then learn again."
    } else {
        debug "Capture looks sane: ${ticks.size()} transitions, ${distinct} distinct values"
    }
}

/*************
 * BASIC UTILS
 */

/**
 * Format a byte[] as a string of space-separated hex bytes,
 * used for the payload of most commands.
 */
String toPayload(final byte[] bytes) {
    return bytes.collect({b -> String.format("%02X", b)}).join(' ') 
}

/**
 * Parse a string of space separated hex bytes (the payload of most messages)
 * as a byte[]
 */
byte[] toBytes(final List<String> payload) {
    return payload.collect({x -> Integer.parseInt(x, 16) as byte}) as byte[]
}

/**
 * Format a struct as a string of space-separated hex bytes.
 * @param format   a description of the struct's byte layout
 * @param payload  a struct to format
 */
String toPayload(final List<Map> format, final Map<String, Object> payload) {
    final def output = new ByteArrayOutputStream()
    for (def entry in format) {
        def value = payload[entry.name]
        switch (entry.type) {
        case "uint8": writeIntegerLe(output, value, 1); break
        case "uint16": writeIntegerLe(output, value, 2); break
        case "uint24": writeIntegerLe(output, value, 3); break
        case "uint32": writeIntegerLe(output, value, 4); break
        case "octetStr": 
            writeIntegerLe(output, value.length, 1)
            output.write(value, 0, value.length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return toPayload(output.toByteArray())
}

/**
 * Parse a struct from a string of space-separated hex bytes
 * @param format   a description of the struct's byte layout
 * @param payload  a string of space-separate hex bytes
 */
Map toStruct(final List<Map> format, final List<String> payload) {
    final def input = new ByteArrayInputStream(toBytes(payload))
    final def result = [:]
    for (def entry in format) {
        switch (entry.type) {
        case "uint8":  result[entry.name] = readIntegerLe(input, 1); break
        case "uint16": result[entry.name] = readIntegerLe(input, 2); break
        case "uint24": result[entry.name] = readIntegerLe(input, 3); break
        case "uint32": result[entry.name] = readIntegerLe(input, 4); break
        case "octetStr": 
            final int length = readIntegerLe(input, 1)
            result[entry.name] = new byte[length]
            input.read(result[entry.name], 0, length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return result
}

/**
 * Write an integer in twos complement little endian byte order to the given
 * output stream, taking up the number of bytes given
 */
def writeIntegerLe(final ByteArrayOutputStream out, int value, final int numBytes) { 
    for (int p = 0; p < numBytes; p++) { 
        final int digit1 = value % 16
        value = value.intdiv(16)
        final int digit2 = value % 16 
        out.write(digit2 * 16 + digit1)
        value = value.intdiv(16)
    }
}

/**
 * Read `numBytes` bytes from the input stream as an integer in twos complement litle endian order
 */
def readIntegerLe(final ByteArrayInputStream input, final int numBytes) {
    int value = 0
    int pos = 1
    for (int i = 0; i < numBytes; i++) {
        value += input.read()*pos
        pos *= 0x100
    }
    return value
}

/**
 * @return the next value in a sequence, persisted in the driver state
 */
def nextSeq() {
    return state.nextSeq = ((state.nextSeq ?: 0) + 1) % 0x10000;
}

/**
 * Checksum used to ensure the code parts are assembled correctly
 * @return the sum of all bytes in the byte array, mod 256 
 *  (yes, this is a terrible CRC as the order could be completely wrong and still get the right value)
 */
def checksum(final byte[] byteArray) {
    // Java/Groovy bytes are signed, Byte.toUnsignedInt gets us the right integer value
    return byteArray.inject(0, {acc, val -> acc + Byte.toUnsignedInt(val)}) % 0x100
}

/**
 * Logging helpers
 * Why does Hubitat's LogWrapper even have these separate methods if this isn't built in??
 */
def error(msg) {
    log.error(msg)
}
def warn(msg) {
    if (logLevel == "WARN" || logLevel == "INFO" || logLevel == "DEBUG") {
        log.warn(msg)
    }
}
def info(msg) {
    if (logLevel == "INFO" || logLevel == "DEBUG") {
        log.info(msg)
    }
}
def debug(msg) {
    if (logLevel == "DEBUG") {
        log.debug(msg)
    }
}
def resetLogLevel() {
    info "logLevel auto reset to INFO"
    device.updateSetting("logLevel", [value:"INFO", type:"enum"])
}

/*************
 * MOCKING STUBS
 */

/**
 * Determine if hub commands should be mocked (based on the presence of variables from the unit tests)
 */
def mockHubCommands() {
    try {
        return sentCommands != null
    } catch (ex) {
        return false
    }
}

/**
 * Mocking facade for sendHubCommand
 */
def doSendHubCommand(cmd) {
    if (mockHubCommands()) {
        sentCommands.add(cmd)
    } else {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    }
}

/**
 * Mocking facade for sendEvent
 */
def doSendEvent(final Map event) {
    if (mockHubCommands()) {
        sentEvents.add(event)
    } else {
        sendEvent(event)
    }
}

/**
 * Alternative to direct org.apache.commons.codec.binary.Base64 usage
 * so we don't have to have that dependency in tests
 */
def encodeBase64(final byte[] bytes) {
    try {
        return org.apache.commons.codec.binary.Base64.encodeBase64String(bytes)
    } catch (ex) {
        // Fallback for tests
        return encodeToString(bytes)
    }
}

/**
 * Counterpart to encodeBase64, with the same fallback reasoning: prefer the commons
 * codec, fall back to the Groovy extension method if it is unavailable.
 */
def decodeBase64(final String s) {
    try {
        return org.apache.commons.codec.binary.Base64.decodeBase64(s)
    } catch (ex) {
        return s.decodeBase64()
    }
}

/**
 * Alternative to zigbee.command so we don't have to stub that
 */
String command(final int clusterId, final int commandId, final String payload) {
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${Integer.toHexString(clusterId)} 0x${Integer.toHexString(commandId)} {${payload}}"
    //return zigbee.command(clusterId, commandId, payload)[0]
}

/**
 * Read one attribute. Same hand-rolled approach as command() above, for the same
 * reason -- avoids depending on zigbee.readAttribute in tests. The response comes
 * back as command 0x01 rather than a 0x0A report, but parse() dispatches on cluster
 * and the handlers key off attrInt, so both land in the same place.
 */
String readAttribute(final int clusterId, final int attributeId) {
    return "he rattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${Integer.toHexString(clusterId)} 0x${Integer.toHexString(attributeId)}"
}