# Scooter Speedometer

A full-screen, offline Android GPS speedometer designed for scooters and mopeds.

## Features

- Large digital speed display with animated gauge arc
- Tap anywhere to switch MPH / KM/H
- GPS accuracy and satellites-used status
- Smoothed GPS speed with low-speed noise filtering
- Maximum speed and trip distance saved between sessions
- Hold the screen for 1.1 seconds to reset trip and maximum speed
- Immersive full-screen, high-contrast night-friendly display
- Keeps the screen awake while riding
- No ads, accounts, analytics, or internet permission
- Current song, artist, and album artwork from compatible music apps
- Previous, play/pause, and next controls above the speedometer
- Dimmed album artwork automatically becomes the dashboard background
- Destination search with Google Maps, Waze, or another installed navigation app
- Movable speed and music dashboard overlay that remains visible during navigation
- GPS-based live weather widget with temperature, conditions, rain chance, and wind
- Resizable weather widget with a touch-drag corner grip and saved dimensions
- Animated HTC Sense-style weather backgrounds for sun, clouds, rain, snow, fog, and storms
- Official live National Weather Service alert banners and Android notifications
- Built-in media volume down/up controls with live volume percentage
- Accuracy-aware adaptive GPS filtering with spike rejection and stationary-drift suppression
- Custom dashboard editor with draggable/resizable media, speedometer, and navigation sections
- Full and compact portrait/landscape presets, five gauge colors, three gauge styles, album-art opacity, and three saved layout slots
- Built-in, key-free OpenStreetMap navigation with route line, ETA, live GPS following, turn cards, spoken maneuvers, and rerouting
- Spoken 15-minute rain, thunderstorm, snow, and dangerous-wind forecasts with duplicate cooldowns
- Spoken National Weather Service alerts and tap-to-mute weather voice control
- Freely movable weather widget with independent four-corner width/height resizing
- Long-press weather skin gallery: HTC Sense glass, neon cyan, minimal clear, retro amber, and storm radar
- Branded launch screen featuring the BobTheZombie scooter mascot and developer credit
- Real-time animated album artwork with cinematic pan, zoom, pulse, rotation, and shifting neon glow while music plays
- One-tap full-screen front-camera backup view with mirrored/normal modes and high-contrast parking guides

## Install

Copy `Scooter-Speedometer-v1.0.apk` to the Android phone, open it, allow installation from the chosen file-manager when prompted, then grant precise location access. Turn on Android Location/GPS and mount the phone with a clear view of the sky.

GPS speed is most reliable outdoors after satellite lock. This app is a supplemental display; do not interact with it while moving.

## Music controls

Tap **Enable Music Controls** in the app and allow **Scooter Speedometer** under Android's Notification Access settings. This lets the dash display and control Spotify, YouTube Music, Pandora, and most other Android media players. The app does not read, store, or transmit notification content.

## Navigation overlay

Tap **Navigate**, grant **Display over other apps** once, enter a destination, and choose Google Maps, Waze, or another navigation app. Navigation opens normally with a compact scooter dashboard floating above it. Drag the speed section vertically to reposition the dashboard, or tap **×** to close it.

Choose **Built-in free navigation** to stay inside Scooter Speedometer. The embedded OpenStreetMap view follows the scooter, draws the route, displays the next maneuver and ETA, speaks turns, and requests a fresh route after a meaningful deviation. Address search uses Nominatim and online route calculation uses the community OSRM service, so this mode needs a data connection. Google Maps and Waze remain available as fallbacks.

## Weather widget

Current weather is loaded automatically from the scooter's GPS position and refreshed about every 10 minutes. The widget shows temperature, conditions, precipitation probability, wind direction, and wind speed. Recent weather remains visible if the connection drops. Weather data is provided by Open-Meteo.

Drag the weather widget's body to place it anywhere on the dashboard. Drag any of its four highlighted corners to resize it horizontally and vertically. Position and dimensions are restored the next time the app opens.

Tap the weather widget to enable or mute voice weather. Press and hold it to choose between HTC Sense glass, neon cyan, minimal clear, retro amber, and storm radar skins. Forecast speech checks 15-minute Open-Meteo intervals and warns of approaching rain, thunderstorms, snow, or potentially dangerous wind gusts. Repeated forecasts are suppressed for 45 minutes, while each new moderate-or-higher NWS alert is announced once.

## Launch screen

Every launch opens with a short branded screen showing the Scooter Speedometer gauge logo, the original BobTheZombie scooter character, and the **Developed by BobTheZombie** credit before the live dashboard initializes.

The full dashboard background reacts to current conditions with animated sunlight, drifting clouds, rain, snow, fog, or lightning. In the United States, active National Weather Service alerts are checked approximately every five minutes. Moderate, severe, and extreme alerts produce an Android notification; tapping the alert banner opens its full instructions.

## Dashboard customization

Tap **Customize** at the bottom of the dashboard. Choose **Drag & resize sections**, then drag the media, speedometer, or navigation outline to reposition it; drag its lower-right dot to resize it. Tap **Done** to keep the layout. The customization menu also includes compact and full portrait/landscape presets, album-art transparency, gauge color and style choices, and three reusable saved-layout slots.

## Backup camera

Tap **Backup Cam** on the dashboard and grant camera permission the first time. The app opens the phone's front camera as a full-screen, mirrored reverse-view display with green/yellow/red guide lines. Tap **Mirrored** to switch to a normal camera orientation, or **Close** to return to the speedometer. The view is live only: the app does not record, photograph, save, or upload camera data.

This is a supplemental view, not a certified reversing system. It can only show the area behind the scooter when the selected front lens is physically aimed there. Stop first, check the surroundings directly, and never rely on the screen alone.
