# DeviceSync

DeviceSync is an Android client foundation for local synchronization with a Windows computer.

## Security Note

At this stage a paired device is only a saved known device. It is not yet a cryptographically trusted device.

## Technical Debt

The Android package/application id is still `com.example.devicesync`. Rename it before publication as a separate refactor after the networking layer is stable.

## Manual TCP Test

1. Make sure the Android phone and Windows computer are connected to the same local network.
2. Start a compatible DeviceSync test server on Windows. It must accept TCP connections and respond to `connection.hello` with `connection.hello_ack` using the 4-byte big-endian length prefix.
3. Find the Windows computer's local IPv4 address, for example in Windows network settings or with `ipconfig`.
4. Open DeviceSync on Android, choose Add computer, then manual IP entry.
5. Enter the Windows IPv4 address and the test server port, then tap Connect.
6. Windows Firewall may ask for permission. Allow the specific test server application or the local port for the private network.
7. The Android app should show a connected state and open the device details screen after receiving `connection.hello_ack`.

Do not disable Windows Firewall entirely for this test.

## Heartbeat Test

1. Start the Windows test server.
2. Connect Android to the server.
3. Confirm that `connection.ping` messages receive matching `connection.pong` responses.
4. Stop the Windows server without sending `connection.close`.
5. Confirm that Android detects the lost connection after missed pong responses.
6. Start the Windows server again.
7. Confirm that Android schedules reconnect attempts when auto-connect is enabled.

## App Restart Test

1. Connect to Windows.
2. Close the Android app.
3. Start it again.
4. Confirm that the Android Device ID did not change.
5. Confirm that the computer remains in the device list.
6. Confirm that saved devices start as offline rather than falsely connected.

## Duplicate Message Test

1. Make the test server send the same framed message twice with the same `senderDeviceId` and `messageId`.
2. Confirm that the handler processes it once.
3. Confirm that Android sends `message.ack` again for the duplicate.
