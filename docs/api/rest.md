---
description: Lavalink REST API documentation.
---

# REST API

Lavalink exposes a REST API to allow for easy control of the players.
Most routes require the `Authorization` header with the configured password.

```
Authorization: youshallnotpass
```

Routes are prefixed with `/v3` as of `v3.7.0` and `/v4` as of `v4.0.0`. Routes without an API prefix were removed in v4 (except `/version`).

## Insomnia Collection

You can find an [Insomnia](https://insomnia.rest/) collection [here](Insomnia.json) which contains all the endpoints and their respective payloads.

## Error Responses

When Lavalink encounters an error, it will respond with a JSON object containing more information about the error. Include the `trace=true` query param to also receive the full stack trace.

| Field     | Type   | Description                                                                 |
|-----------|--------|-----------------------------------------------------------------------------|
| timestamp | int    | The timestamp of the error in milliseconds since the Unix epoch             |
| status    | int    | The HTTP status code                                                        |
| error     | string | The HTTP status code message                                                |
| trace?    | string | The stack trace of the error when `trace=true` as query param has been sent |
| message   | string | The error message                                                           |
| path      | string | The request path                                                            |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "timestamp": 1667857581613,
  "status": 404,
  "error": "Not Found",
  "trace": "...",
  "message": "Session not found",
  "path": "/v4/sessions/xtaug914v9k5032f/players/817327181659111454"
}
```

</details>

## Track API

### Common Types ### {: #track-api-types }

#### Track

| Field      | Type                             | Description                                                                     |
|------------|----------------------------------|---------------------------------------------------------------------------------|
| encoded    | string                           | The base64 encoded track data                                                   |
| info       | [Track Info](#track-info) object | Info about the track                                                            |
| pluginInfo | object                           | Additional track info provided by plugins                                       |
| userData   | object                           | Additional track data provided via the [Update Player](#update-player) endpoint |

#### Track Info

| Field      | Type    | Description                                                                           |
|------------|---------|---------------------------------------------------------------------------------------|
| identifier | string  | The track identifier                                                                  |
| isSeekable | bool    | Whether the track is seekable                                                         |
| author     | string  | The track author                                                                      |
| length     | int     | The track length in milliseconds                                                      |
| isStream   | bool    | Whether the track is a stream                                                         |
| position   | int     | The track position in milliseconds                                                    |
| title      | string  | The track title                                                                       |
| uri        | ?string | The track uri                                                                         |
| artworkUrl | ?string | The track artwork url                                                                 |
| isrc       | ?string | The track [ISRC](https://en.wikipedia.org/wiki/International_Standard_Recording_Code) |
| sourceName | string  | The track source name                                                                 |

#### Playlist Info

| Field         | Type   | Description                                                     |
|---------------|--------|-----------------------------------------------------------------|
| name          | string | The name of the playlist                                        |
| selectedTrack | int    | The selected track of the playlist (-1 if no track is selected) |

---

### Track Loading

This endpoint is used to resolve audio tracks for use with the [Update Player](#update-player) endpoint.

!!! tip

    Lavalink supports searching via YouTube, YouTube Music, and Soundcloud. To search, you must prefix your identifier with `ytsearch:`, `ytmsearch:` or `scsearch:` respectively.
    
    When a search prefix is used, the returned `loadType` will be `search`. Note that disabling the respective source managers renders these search prefixes useless.

    Plugins may also implement prefixes to allow for more search engines to be utilised.

```
GET /v4/loadtracks?identifier=dQw4w9WgXcQ
```

Response:

#### Track Loading Result

| Field    | Type                                 | Description            |       
|----------|--------------------------------------|------------------------|
| loadType | [LoadResultType](#load-result-type)  | The type of the result | 
| data     | ?[LoadResultData](#load-result-data) | The data of the result |

#### Load Result Type

| Load Result Type | Description                                   |
|------------------|-----------------------------------------------|
| `track`          | A track has been loaded                       |
| `playlist`       | A playlist has been loaded                    |
| `search`         | A search result has been loaded               |
| `empty`          | There has been no matches for your identifier |
| `error`          | Loading has failed with an error              |

#### Load Result Data

##### Track Result Data

[Track](#track) object with the loaded track.

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "loadType": "track",
  "data": {
    "encoded": "...",
    "info": { ... },
    "pluginInfo": { ... },
    "userData": { ... }
  }
}
```

</details>

##### Playlist Result Data

| Field      | Type                                  | Description                                  |
|------------|---------------------------------------|----------------------------------------------|
| info       | [PlaylistInfo](#playlist-info) object | The info of the playlist                     |
| pluginInfo | Object                                | Additional playlist info provided by plugins |
| tracks     | array of [Track](#track) objects      | The tracks of the playlist                   |

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "loadType": "playlist",
  "data": {
    "info": { ... },
    "pluginInfo": { ... },
    "tracks": [ ... ]
  }
}
```

</details>

##### Search Result Data

Array of [Track](#track) objects from the search result.

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "loadType": "search",
  "data": [
    {
      "encoded": "...",
      "info": { ... },
      "pluginInfo": { ... },
      "userData": { ... }
    },
    ...
  ]
}
```

</details>

##### Empty Result Data

`null`.

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "loadType": "empty",
  "data": null
}
```

</details>

##### Error Result Data

[Exception](websocket.md#exception-object) object with the error.

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "loadType": "error",
  "data": {
    "message": "Something went wrong",
    "severity": "fault",
    "cause": "...",
    "causeStackTrace": "...",
  }
}
```

</details>

---

### Track Decoding

Decode a single track into its info, where `BASE64` is the encoded base64 data.

```
GET /v4/decodetrack?encodedTrack=BASE64
```

Response:

[Track](#track) object

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "encoded": "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
  "info": {
    "identifier": "dQw4w9WgXcQ",
    "isSeekable": true,
    "author": "RickAstleyVEVO",
    "length": 212000,
    "isStream": false,
    "position": 0,
    "title": "Rick Astley - Never Gonna Give You Up",
    "uri": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "artworkUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
    "isrc": null,
    "sourceName": "youtube"
  },
  "pluginInfo": { ... },
  "userData": { ... }
}
```

</details>

---

Decodes multiple tracks into their info

```
POST /v4/decodetracks
```

Request:

Array of track data strings

<details markdown="1">
<summary>Example Payload</summary>

```yaml
[
  "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
  ...
]
```

</details>

Response:

Array of [Track](#track) objects

<details markdown="1">
<summary>Example Payload</summary>

```yaml
[
  {
    "encoded": "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
    "info": {
      "identifier": "dQw4w9WgXcQ",
      "isSeekable": true,
      "author": "RickAstleyVEVO",
      "length": 212000,
      "isStream": false,
      "position": 0,
      "title": "Rick Astley - Never Gonna Give You Up",
      "uri": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "artworkUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "isrc": null,
      "sourceName": "youtube"
    },
    "pluginInfo": { ... },
    "userData": { ... }
  },
  ...
]
```

</details>

---

## Player API

### Common Types ### {: #player-api-types }

#### Player

| Field   | Type                                             | Description                                           |
|---------|--------------------------------------------------|-------------------------------------------------------|
| guildId | string                                           | The guild id of the player                            |
| track   | ?[Track](#track) object                          | The currently playing track                           |
| volume  | int                                              | The volume of the player, range 0-1000, in percentage |
| paused  | bool                                             | Whether the player is paused                          |
| state   | [Player State](websocket.md#player-state) object | The state of the player                               |
| voice   | [Voice State](#voice-state) object               | The voice state of the player                         |
| filters | [Filters](#filters) object                       | The filters used by the player                        |

#### Voice State

| Field     | Type        | Description                                           |
|-----------|-------------|-------------------------------------------------------|
| token     | string      | The Discord voice token to authenticate with          |
| endpoint  | string      | The Discord voice endpoint to connect to              |
| sessionId | string      | The Discord voice session id to authenticate with     |
| channelId | ?string[^1] | The Discord voice channel id the bot is connecting to |

[^1]: `channelId` is not nullable when updating the player.

`token`, `endpoint`, `sessionId` and `channelId` are the 4 required values for connecting to one of Discord's voice servers.
`sessionId` & `channelId` are provided by the Voice State Update event sent by Discord, whereas the `endpoint` and `token` are provided
with the Voice Server Update.
Please refer to https://docs.discord.com/developers/events/gateway-events#voice-state-update & https://docs.discord.com/developers/events/gateway-events#voice-server-update

#### Filters

Filters are used in above requests and look like this

| Field          | Type                                             | Description                                                                                  |
|----------------|--------------------------------------------------|----------------------------------------------------------------------------------------------|
| volume?        | float                                            | Adjusts the player volume from 0.0 to 5.0, where 1.0 is 100%. Values >1.0 may cause clipping |
| equalizer?     | array of [Equalizer](#equalizer) objects         | Adjusts 15 different bands                                                                   |
| karaoke?       | [Karaoke](#karaoke) object                       | Eliminates part of a band, usually targeting vocals                                          |
| timescale?     | [Timescale](#timescale) object                   | Changes the speed, pitch, and rate                                                           |
| tremolo?       | [Tremolo](#tremolo) object                       | Creates a shuddering effect, where the volume quickly oscillates                             |
| vibrato?       | [Vibrato](#vibrato) object                       | Creates a shuddering effect, where the pitch quickly oscillates                              |
| rotation?      | [Rotation](#rotation) object                     | Rotates the audio around the stereo channels/user headphones (aka Audio Panning)             |
| distortion?    | [Distortion](#distortion) object                 | Distorts the audio                                                                           |
| channelMix?    | [Channel Mix](#channel-mix) object               | Mixes both channels (left and right)                                                         |
| lowPass?       | [Low Pass](#low-pass) object                     | Filters higher frequencies                                                                   |
| pluginFilters? | map of [Plugin Filters](#plugin-filters) objects | Filter plugin configurations                                                                 |

##### Equalizer

There are 15 bands (0-14) that can be changed.
"gain" is the multiplier for the given band. The default value is 0. Valid values range from -0.25 to 1.0,
where -0.25 means the given band is completely muted, and 0.25 means it is doubled. Modifying the gain could also change the volume of the output.

<details markdown="1">
<summary>Band Frequencies</summary>

| Band | Frequency |
|------|-----------|
| 0    | 25 Hz     |
| 1    | 40 Hz     |
| 2    | 63 Hz     |
| 3    | 100 Hz    |
| 4    | 160 Hz    |
| 5    | 250 Hz    |
| 6    | 400 Hz    |
| 7    | 630 Hz    |
| 8    | 1000 Hz   |
| 9    | 1600 Hz   |
| 10   | 2500 Hz   |
| 11   | 4000 Hz   |
| 12   | 6300 Hz   |
| 13   | 10000 Hz  |
| 14   | 16000 Hz  |

</details>

| Field | Type  | Description             |
|-------|-------|-------------------------|
| band  | int   | The band (0 to 14)      |
| gain  | float | The gain (-0.25 to 1.0) |

##### Karaoke

Uses equalization to eliminate part of a band, usually targeting vocals.

| Field        | Type  | Description                                                             |
|--------------|-------|-------------------------------------------------------------------------|
| level?       | float | The level (0 to 1.0 where 0.0 is no effect and 1.0 is full effect)      |
| monoLevel?   | float | The mono level (0 to 1.0 where 0.0 is no effect and 1.0 is full effect) |
| filterBand?  | float | The filter band (in Hz)                                                 |
| filterWidth? | float | The filter width                                                        |

##### Timescale

Changes the speed, pitch, and rate. All default to 1.0.

| Field  | Type  | Description                |
|--------|-------|----------------------------|
| speed? | float | The playback speed 0.0 ≤ x |
| pitch? | float | The pitch 0.0 ≤ x          |
| rate?  | float | The rate 0.0 ≤ x           |

##### Tremolo

Uses amplification to create a shuddering effect, where the volume quickly oscillates.
Demo: https://en.wikipedia.org/wiki/File:Fuse_Electronics_Tremolo_MK-III_Quick_Demo.ogv

| Field      | Type  | Description                     |
|------------|-------|---------------------------------|
| frequency? | float | The frequency 0.0 < x           |
| depth?     | float | The tremolo depth 0.0 < x ≤ 1.0 |

##### Vibrato

Similar to tremolo. While tremolo oscillates the volume, vibrato oscillates the pitch.

| Field      | Type  | Description                     |
|------------|-------|---------------------------------|
| frequency? | float | The frequency 0.0 < x ≤ 14.0    |
| depth?     | float | The vibrato depth 0.0 < x ≤ 1.0 |

##### Rotation

Rotates the sound around the stereo channels/user headphones (aka Audio Panning). It can produce an effect similar to https://youtu.be/QB9EB8mTKcc (without the reverb).

| Field       | Type  | Description                                                                                              |
|-------------|-------|----------------------------------------------------------------------------------------------------------|
| rotationHz? | float | The frequency of the audio rotating around the listener in Hz. 0.2 is similar to the example video above |

##### Distortion

Distortion effect. It can generate some pretty unique audio effects.

| Field      | Type  | Description    |
|------------|-------|----------------|
| sinOffset? | float | The sin offset |
| sinScale?  | float | The sin scale  |
| cosOffset? | float | The cos offset |
| cosScale?  | float | The cos scale  |
| tanOffset? | float | The tan offset |
| tanScale?  | float | The tan scale  |
| offset?    | float | The offset     |
| scale?     | float | The scale      |

##### Channel Mix

Mixes both channels (left and right), with a configurable factor on how much each channel affects the other.
With the defaults, both channels are kept independent of each other.
Setting all factors to 0.5 means both channels get the same audio.

| Field         | Type  | Description                                           |
|---------------|-------|-------------------------------------------------------|
| leftToLeft?   | float | The left to left channel mix factor (0.0 ≤ x ≤ 1.0)   |
| leftToRight?  | float | The left to right channel mix factor (0.0 ≤ x ≤ 1.0)  |
| rightToLeft?  | float | The right to left channel mix factor (0.0 ≤ x ≤ 1.0)  |
| rightToRight? | float | The right to right channel mix factor (0.0 ≤ x ≤ 1.0) |

##### Low Pass

Higher frequencies get suppressed, while lower frequencies pass through this filter, thus the name low pass.
Any smoothing values equal to or less than 1.0 will disable the filter.

| Field      | Type  | Description                    |
|------------|-------|--------------------------------|
| smoothing? | float | The smoothing factor (1.0 < x) |

##### Plugin Filters

Plugins can add their own filters. The key is the name of the plugin, and the value is the configuration for that plugin. The configuration is plugin specific. See [Plugins](plugins.md) for more plugin information.

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "volume": 1.0,
  "equalizer": [
    {
      "band": 0,
      "gain": 0.2
    }
  ],
  "karaoke": {
    "level": 1.0,
    "monoLevel": 1.0,
    "filterBand": 220.0,
    "filterWidth": 100.0
  },
  "timescale": {
    "speed": 1.0,
    "pitch": 1.0,
    "rate": 1.0
  },
  "tremolo": {
    "frequency": 2.0,
    "depth": 0.5
  },
  "vibrato": {
    "frequency": 2.0,
    "depth": 0.5
  },
  "rotation": {
    "rotationHz": 0
  },
  "distortion": {
    "sinOffset": 0.0,
    "sinScale": 1.0,
    "cosOffset": 0.0,
    "cosScale": 1.0,
    "tanOffset": 0.0,
    "tanScale": 1.0,
    "offset": 0.0,
    "scale": 1.0
  },
  "channelMix": {
    "leftToLeft": 1.0,
    "leftToRight": 0.0,
    "rightToLeft": 0.0,
    "rightToRight": 1.0
  },
  "lowPass": {
    "smoothing": 20.0
  },
  "pluginFilters": {
    "myPlugin": {
      "myPluginKey": "myPluginValue"
    }
  }
}
```

</details>

---

### Get Players

Returns a list of players in this specific session.

```
GET /v4/sessions/{sessionId}/players
```

<details markdown="1">
<summary>Example Payload</summary>

```yaml
[
  {
    "guildId": "...",
    "track": {
      "encoded": "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
      "info": {
        "identifier": "dQw4w9WgXcQ",
        "isSeekable": true,
        "author": "RickAstleyVEVO",
        "length": 212000,
        "isStream": false,
        "position": 60000,
        "title": "Rick Astley - Never Gonna Give You Up",
        "uri": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "artworkUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        "isrc": null,
        "sourceName": "youtube"
      },
      "pluginInfo": { ... },
      "userData": { ... }
    },
    "volume": 100,
    "paused": false,
    "state": {
      "time": 1500467109,
      "position": 60000,
      "connected": true,
      "ping": 50
    },
    "voice": {
      "token": "...",
      "endpoint": "...",
      "sessionId": "..."
    },
    "filters": { ... }
  },
  ...
]
```

</details>

---

### Get Player

Returns the player for this guild in this session.

```
GET /v4/sessions/{sessionId}/players/{guildId}
```

Response:

[Player](#player) object

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "guildId": "...",
  "track": {
    "encoded": "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
    "info": {
      "identifier": "dQw4w9WgXcQ",
      "isSeekable": true,
      "author": "RickAstleyVEVO",
      "length": 212000,
      "isStream": false,
      "position": 60000,
      "title": "Rick Astley - Never Gonna Give You Up",
      "uri": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "artworkUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "isrc": null,
      "sourceName": "youtube"
    }
  },
  "volume": 100,
  "paused": false,
  "state": {
    "time": 1500467109,
    "position": 60000,
    "connected": true,
    "ping": 50
  },
  "voice": {
    "token": "...",
    "endpoint": "...",
    "sessionId": "..."
  },
  "filters": { ... }
}
```

</details>

---

### Update Player

Updates or creates the player for this guild if it doesn't already exist.

```
PATCH /v4/sessions/{sessionId}/players/{guildId}?noReplace=true
```

!!! info

    `sessionId` in the path should be the value from the [ready op](websocket.md#ready-op).

Query Params:

| Field      | Type | Description                                                                  |
|------------|------|------------------------------------------------------------------------------|
| noReplace? | bool | Whether to replace the current track with the new track. Defaults to `false` |

Request:

| Field              | Type                                        | Description                                                                                   |
|--------------------|---------------------------------------------|-----------------------------------------------------------------------------------------------|
| track?             | [Update Player Track](#update-player-track) | Specification for a new track to load, as well as user data to set                            |
| ~~encodedTrack?~~* | ?string                                     | The base64 encoded track to play. `null` stops the current track                              |
| ~~identifier?~~*   | string                                      | The identifier of the track to play                                                           |
| *position*?        | int                                         | The track position in milliseconds                                                            |
| endTime?           | ?int                                        | The track end time in milliseconds (must be > 0). `null` resets this if it was set previously |
| volume?            | int                                         | The player volume, in percentage, from 0 to 1000                                              |
| paused?            | bool                                        | Whether the player is paused                                                                  |
| filters?           | [Filters](#filters) object                  | The new filters to apply. This will override all previously applied filters                   |                   
| voice?             | [Voice State](#voice-state) object          | Information required for connecting to Discord                                                |

!!! info

    \* `encodedTrack` and `identifier` are mutually exclusive and deprecated. Use `track` instead.

#### Update Player Track

| Field        | Type    | Description                                                         |
|--------------|---------|---------------------------------------------------------------------|
| encoded?*    | ?string | The base64 encoded track to play. `null` stops the current track    |
| identifier?* | string  | The identifier of the track to play                                 |
| userData?    | object  | Additional track data to be sent back in the [Track Object](#track) |

!!! info

    \* `encoded` and `identifier` are mutually exclusive.

When `identifier` is used, Lavalink will try to resolve the identifier as a single track. An HTTP `400` error is returned when resolving a playlist, search result, or no tracks.

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "track": {
    "encoded": "...",
    "identifier": "...",
    "userData": { ... }
  },
  "endTime": 0,
  "volume": 100,
  "position": 32400,
  "paused": false,
  "filters": { ... },
  "voice": {
    "token": "...",
    "endpoint": "...",
    "sessionId": "..."
  }
}
```

</details>

Response:

[Player](#player) object

<details markdown="1">
<summary>Example Payload</summary>

```yaml
{
  "guildId": "...",
  "track": {
    "encoded": "QAAAjQIAJVJpY2sgQXN0bGV5IC0gTmV2ZXIgR29ubmEgR2l2ZSBZb3UgVXAADlJpY2tBc3RsZXlWRVZPAAAAAAADPCAAC2RRdzR3OVdnWGNRAAEAK2h0dHBzOi8vd3d3LnlvdXR1YmUuY29tL3dhdGNoP3Y9ZFF3NHc5V2dYY1EAB3lvdXR1YmUAAAAAAAAAAA==",
    "info": {
      "identifier": "dQw4w9WgXcQ",
      "isSeekable": true,
      "author": "RickAstleyVEVO",
      "length": 212000,
      "isStream": false,
      "position": 60000,
      "title": "Rick Astley - Never Gonna Give You Up",
      "uri": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "artworkUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "isrc": null,
      "sourceName": "youtube"
    }
  },
  "volume": 100,
  "paused": false,
  "state": {
    "time": 1500467109,
    "position": 60000,
    "connected": true,
    "ping": 50
  },
  "voice": {
    "token": "...",
    "endpoint": "...",
    "sessionId": "..."
  },
  "filters": { ... }
}
```

</details>

---

### Destroy Player

Destroys the player for this guild in this session.

```
DELETE /v4/sessions/{sessionId}/players/{guildId}
```

Response:

204 - No Content

---

## Crossfade API

The client owns the queue. It may reserve **one** next track per player while the current track plays, and
Lavalink then transitions to it without the client sending another play request:

- If crossfading is enabled and both tracks are long enough, the next track is faded in over the end of the
  current one using an equal-power curve.
- Otherwise (disabled, a stream or unknown length, a track that is too short, or a fade that was cancelled), the
  reserved track starts normally as soon as the current track finishes.

In both cases the client receives exactly one `TrackEndEvent` (`finished`) for the current track followed by one
`TrackStartEvent` for the reserved track. **The client must treat the reserved track as consumed when it sees that
`TrackStartEvent` and must not also send a play request for it**, otherwise the track is restarted. Only a
`finished` end consumes the reservation; `stopped`, `replaced`, `cleanup` and `loadFailed` leave it for the client
to resolve. Clients that never reserve a track see no change in behaviour.

A fade is used only if, when the reservation finishes loading, both the remaining time of the current track and the
duration of the next track are at least *fade duration + 2 seconds*. Streams and tracks of unknown length are never
faded. Pausing or seeking during a fade cancels the overlap and restores the current track at full volume; the
reserved track then starts normally from its beginning. Playing a track, stopping the player, destroying the
player, or deleting the reservation always takes precedence over a reservation and clears it.

The fade finishes about 0.5 seconds before the end of the current track, so a track that is slightly shorter than
its reported length cannot cut the fade short. Filters and volume apply to both tracks. A 5 second lead is used to decode the next track before the fade; if it is
not ready when the fade is due, the overlap is cancelled and the normal transition is used. With a `timescale`
filter the fade is measured in track time at the start and in wall-clock time while mixing.

#### Crossfade Settings

| Field      | Type | Description                      |
|------------|------|----------------------------------|
| enabled    | bool | Whether reserved tracks are faded |
| durationMs | int  | Length of the overlap in ms      |

The server defaults come from `lavalink.server.crossfade` (disabled, 3000 ms). A player can override either value.

#### Automix

Optional cue points that turn a crossfade into an *automix*: the next track can start at a chosen position (for
example its chorus) while the current one leaves at another. All fields are optional and in the units shown. When none
are given the transition is an ordinary crossfade.

| Field        | Type   | Description                                                                                                  |
|--------------|--------|--------------------------------------------------------------------------------------------------------------|
| exitMs?      | int    | Position in the **current** track where the transition begins. When absent, the fade is anchored to its end  |
| entryMs?     | int    | Position in the **next** track where it starts. When absent, it starts from the beginning                    |
| entryGainDb? | float  | Gain of the next track at the start of the overlap, ramping to 0 dB by its end (-30 to 12). Use a negative value when the next track is louder |
| fadeMs?      | int    | Length of this overlap in ms (100 to 30000), instead of the player's setting                                 |
| effects?     | [Automix Effects](#automix-effects) | Optional effects that process the outgoing track before the overlap                  |

The transition begins when the current track reaches `exitMs`, with the next track starting at `entryMs`, so give both
at bar downbeats and the two will line up. When the overlap ends the current track is cut, even though it has more
audio. The server does not stretch tempo, so the client should only pair tracks whose tempos are compatible (or use a
shorter `fadeMs`).

The server checks the cues against both tracks. They are used only if the current track still has at least 2 s until
`exitMs` and `fadeMs` after it, the next track has at least `fadeMs` + 2 s after `entryMs`, and a non-zero `entryMs`
is seekable. Otherwise it falls back to an ordinary crossfade, and then to a normal transition, and the response says
which. If crossfading is disabled for the player, automix cues are ignored and a reserved track always starts normally
from its beginning when the current one ends.

#### Automix Effects

Effects that sound like a DJ transition. They need **lead-in time**: for `leadMs` before the overlap the outgoing track plays
on its own while the effects build, so the exit point must be at least `leadMs` + 2 s ahead of the playback position. Every
field is optional, and an effect that cannot be done is dropped (the response shows which were kept) rather than failing.

| Field        | Type  | Description                                                                                                  |
|--------------|-------|--------------------------------------------------------------------------------------------------------------|
| muffleExit?  | bool  | A low-pass closes on the **outgoing** track over the lead-in and the overlap, so it sounds like it moves away |
| muffleEntry? | bool  | The **incoming** track starts muffled and the low-pass opens over the overlap                                |
| muffleHz?    | int   | How low the cutoff goes, 100 to 8000 (default 700)                                                           |
| leadMs?      | int   | Lead-in length, 0 to 20000 (default 6000, or 8000 with `beatSync`); shortened if the track does not have the time, dropped below 1000 |
| currentBpm?  | float | Tempo of the outgoing track at the transition (40 to 250). Needed for `tempoMatch` and `beatSync`           |
| nextBpm?     | float | Tempo of the incoming track at its entry point                                                               |
| tempoMatch?  | bool  | Stretch the outgoing track toward the incoming tempo during the lead-in, **keeping its pitch**. The target is the nearest simple multiple of the incoming tempo (same, double, half, 3:2, 2:3, 4:3 or 3:4), so 170 into 110 BPM stretches 3% to 165, not 35% to 110. At most 10% change; otherwise ignored |
| beatSync?    | bool  | Measure where the beats actually fall in the audio of both tracks and line them up (see below)               |

**Layering and muffling.** The overlap is layered, not a swap. The incoming track comes in **at once**, muffled, and reaches full level
within the first 12% of the overlap. The outgoing track keeps its full level while a 4th-order low-pass (the cutoff glides, so no clicks)
closes it to `muffleHz` over the first half. In the second half the outgoing track fades away while the incoming track's low-pass opens.
So you hear A, then A muffled together with a muffled B, then B opening up. The lead-in itself is not muffled.

**Events.** `TrackEndEvent` for the outgoing track and `TrackStartEvent` for the incoming one are sent together **when the overlap
finishes**. If the outgoing track's audio ends by itself earlier, its end is held back until then.

**Tempo matching** time-stretches the outgoing track over the first 40% of the lead-in and holds the new tempo until the overlap.
Only the outgoing track is stretched; the incoming one always plays at its own tempo.

**Beat sync** exists because cue points come from an analysis of *another recording* of the song (for example Spotify's), whose
beat grid can be off by tens or hundreds of milliseconds against the audio actually played. The server measures the beats in the
last seconds of the outgoing track and in the first seconds of the incoming one (it starts the incoming track a beat early for
this), starts the overlap on one of the outgoing track's beats and trims the incoming track to begin on one of its own. It
aligns beats, not bars: which beat is the first of a bar still comes from your cue points. The alignment resolution is one 20 ms
frame, and when the beats are not clear enough to trust (a strict confidence threshold), it does nothing and the cue points are used
as they are. It needs `currentBpm` and `nextBpm`, and at least 4.5 s of lead-in (7.5 s with `tempoMatch`).

#### Next Track

| Field      | Type                  | Description                                                                                           |
|------------|-----------------------|-------------------------------------------------------------------------------------------------------|
| track      | [Track](#track)       | The reserved track                                                                                    |
| crossfade  | bool                  | Whether it is currently scheduled to be faded in instead of started normally                          |
| transition | string                | What will actually happen: `automix`, `crossfade` (including when automix cues could not be used) or `normal` |
| automix    | [Automix](#automix)?  | The cues in effect, only when `transition` is `automix`                                               |

#### Crossfade State

| Field    | Type                                          | Description                                  |
|----------|-----------------------------------------------|----------------------------------------------|
| settings | [Crossfade Settings](#crossfade-settings)     | The effective settings for this player       |
| fading   | bool                                          | Whether an overlap is in progress right now  |
| next     | [Next Track](#next-track)?                    | The reserved track, if any                   |

<details markdown="1">
<summary>Example: automix payload</summary>

```json
{
  "encoded": "QAAAjQM...",
  "automix": {
    "exitMs": 166380,
    "entryMs": 54120,
    "entryGainDb": -3.36
  }
}
```

</details>

#### Deriving cues from an audio analysis

The server does not analyse audio; the client chooses the cues. With a Spotify-style analysis (sections with loudness,
plus bars and beats), a workable recipe is:

1. **Landing point (current track):** the end of a loud section, such as the last chorus, snapped to a bar start. The
   overlap should finish there, so send **`exitMs = landing - fadeMs`**. Skip tracks whose last section is a fade-out.
2. **Landing point (next track):** the start of its first chorus or loudest section, snapped to a bar start. Send
   **`entryMs = landing - fadeMs`** (if that is before the start of the song, send no entry): the song starts in its
   build-up, and its chorus arrives just as the overlap finishes.
3. **Gain:** `entryGainDb = loudness(current exit section) - loudness(next entry section)`, limited to 0 or below, so a
   louder next track is brought down to match and ramps back up during the overlap.
4. **Length:** a few bars at the current tempo (`fadeMs = 60000 / bpm * 4 * bars`), for example 2 bars, or 4 with effects so the
   songs are layered for a while.
5. **Skip automix** when the tempos differ a lot, or when no suitable section exists, and send no `automix` at all.

---

### Get Crossfade State

```
GET /v4/sessions/{sessionId}/players/{guildId}/crossfade
```

Response: a [Crossfade State](#crossfade-state). 404 if the player does not exist.

---

### Update Crossfade Settings

Sets player-level overrides. A present `null` resets the value to the server default. The new settings are
re-evaluated for an already reserved track, except while a fade is in progress.

```
PATCH /v4/sessions/{sessionId}/players/{guildId}/crossfade
```

Request:

| Field       | Type   | Description                                      |
|-------------|--------|--------------------------------------------------|
| enabled?    | bool?  | Enable or disable fading for this player         |
| durationMs? | int?   | Fade length in ms, between 100 and 30000         |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "enabled": true,
  "durationMs": 4000
}
```

</details>

Response: a [Crossfade State](#crossfade-state).

---

### Reserve Next Track

Reserves, or replaces, the next track. The request completes when the track has loaded, so the response is the
loading outcome. **Only a `200` means this track is reserved; any other response means nothing is reserved**, including
the track this request replaced. The server never falls back to an older reservation, so the client can treat an error
like any failed next-track load. The client always wins: if a fade is running, it is cancelled and the current track
is restored.

```
PUT /v4/sessions/{sessionId}/players/{guildId}/crossfade/next
```

Request: exactly one of `encoded` and `identifier`.

| Field       | Type                | Description                               |
|-------------|---------------------|-------------------------------------------|
| encoded?    | string              | The base64 encoded track                  |
| identifier? | string              | The identifier to load                    |
| userData?   | object              | Custom data stored on the track           |
| automix?    | [Automix](#automix) | Optional cue points, see above            |

Response: a [Next Track](#next-track).

| Status | Meaning                                                                                  |
|--------|------------------------------------------------------------------------------------------|
| 400    | Invalid body or automix values, the track could not be decoded or loaded, or the identifier is not a track |
| 404    | The session or player does not exist                                                     |
| 409    | Nothing is playing (or it stopped while the track loaded), or a newer request replaced this one |

---

### Get Next Track

```
GET /v4/sessions/{sessionId}/players/{guildId}/crossfade/next
```

Response: a [Next Track](#next-track), or 404 if none is reserved.

---

### Clear Next Track

Releases the reservation. During a fade this cancels the overlap and restores the current track.

```
DELETE /v4/sessions/{sessionId}/players/{guildId}/crossfade/next
```

Response:

204 - No Content

---

## Session API

### Update Session

Updates the session with the resuming state and timeout.

```
PATCH /v4/sessions/{sessionId}
```

Request:

| Field     | Type | Description                                         |
|-----------|------|-----------------------------------------------------|
| resuming? | bool | Whether resuming is enabled for this session or not |
| timeout?  | int  | The timeout in seconds (default is 60s)             |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "resuming": false,
  "timeout": 0
}
```

</details>

Response:

| Field    | Type | Description                                         |
|----------|------|-----------------------------------------------------|
| resuming | bool | Whether resuming is enabled for this session or not |
| timeout  | int  | The timeout in seconds (default is 60s)             |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "resuming": true,
  "timeout": 60
}
```

</details>

---

## Get Lavalink info

Request Lavalink information.

```
GET /v4/info
```

Response:

### Info Response

| Field          | Type                                      | Description                                                     |
|----------------|-------------------------------------------|-----------------------------------------------------------------|
| version        | [Version](#version-object) object         | The version of this Lavalink server                             |
| buildTime      | int                                       | The millisecond unix timestamp when this Lavalink jar was built |
| git            | [Git](#git-object) object                 | The git information of this Lavalink server                     |
| jvm            | string                                    | The JVM version this Lavalink server runs on                    |
| lavaplayer     | string                                    | The Lavaplayer version being used by this server                |
| sourceManagers | array of strings                          | The enabled source managers for this server                     |
| filters        | array of strings                          | The enabled filters for this server                             |
| plugins        | array of [Plugin](#plugin-object) objects | The enabled plugins for this server                             |

#### Version Object

Parsed [Semantic Versioning 2.0.0](https://semver.org/)

| Field      | Type    | Description                                                                        |
|------------|---------|------------------------------------------------------------------------------------|
| semver     | string  | The full version string of this Lavalink server                                    |
| major      | int     | The major version of this Lavalink server                                          |
| minor      | int     | The minor version of this Lavalink server                                          |
| patch      | int     | The patch version of this Lavalink server                                          |
| preRelease | ?string | The pre-release version according to semver as a `.` separated list of identifiers |
| build      | ?string | The build metadata according to semver as a `.` separated list of identifiers      |

#### Git Object

| Field      | Type   | Description                                                    |
|------------|--------|----------------------------------------------------------------|
| branch     | string | The branch this Lavalink server was built on                   |
| commit     | string | The commit this Lavalink server was built on                   |
| commitTime | int    | The millisecond unix timestamp for when the commit was created |

#### Plugin Object

| Field   | Type   | Description               |
|---------|--------|---------------------------|
| name    | string | The name of the plugin    |
| version | string | The version of the plugin |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "version": {
    "semver": "3.7.0-rc.1+test",
    "major": 3,
    "minor": 7,
    "patch": 0,
    "preRelease": "rc.1",
    "build": "test"
  },
  "buildTime": 1664223916812,
  "git": {
    "branch": "master",
    "commit": "85c5ab5",
    "commitTime": 1664223916812
  },
  "jvm": "18.0.2.1",
  "lavaplayer": "1.3.98.4-original",
  "sourceManagers": [
    "youtube",
    "soundcloud"
  ],
  "filters": [
    "equalizer",
    "karaoke",
    "timescale",
    "channelMix"
  ],
  "plugins": [
    {
      "name": "some-plugin",
      "version": "1.0.0"
    },
    {
      "name": "foo-plugin",
      "version": "1.2.3"
    }
  ]
}
```

</details>

---

## Get Lavalink version

Request Lavalink version.

```
GET /version
```

Response:

```
4.0.0
```

---

## Get Lavalink stats

Request Lavalink statistics.

```
GET /v4/stats
```

Response:

`frameStats` is always missing for this endpoint.
[Stats](websocket.md#stats-object) object

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "players": 1,
  "playingPlayers": 1,
  "uptime": 123456789,
  "memory": {
    "free": 123456789,
    "used": 123456789,
    "allocated": 123456789,
    "reservable": 123456789
  },
  "cpu": {
    "cores": 4,
    "systemLoad": 0.5,
    "lavalinkLoad": 0.5
  }
}
```

</details>

---

## RoutePlanner API

Additionally, there are a few REST endpoints for the ip rotation extension.

### Common Types ### {: #route-planner-api-types }

#### Route Planner Types

| Route Planner Type           | Description                                                                                                                 |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `RotatingIpRoutePlanner`     | IP address used is switched on ban. Recommended for IPv4 blocks or IPv6 blocks smaller than a /64.                          |
| `NanoIpRoutePlanner`         | IP address used is switched on clock update. Use with at least 1 /64 IPv6 block.                                            |
| `RotatingNanoIpRoutePlanner` | IP address used is switched on clock update, rotates to a different /64 block on ban. Use with at least 2x /64 IPv6 blocks. |
| `BalancingIpRoutePlanner`    | IP address used is selected at random per request. Recommended for larger IP blocks.                                        |

#### Details Object

| Field               | Type                                                  | Description                                                                           | Valid Types                                        |
|---------------------|-------------------------------------------------------|---------------------------------------------------------------------------------------|----------------------------------------------------|
| ipBlock             | [IP Block](#ip-block-object) object                   | The ip block being used                                                               | all                                                |
| failingAddresses    | array of [Failing Addresses](#failing-address-object) | The failing addresses                                                                 | all                                                |
| rotateIndex         | string                                                | The number of rotations                                                               | `RotatingIpRoutePlanner`                           |
| ipIndex             | string                                                | The current offset in the block                                                       | `RotatingIpRoutePlanner`                           |
| currentAddress      | string                                                | The current address being used                                                        | `RotatingIpRoutePlanner`                           |
| currentAddressIndex | string                                                | The current offset in the ip block                                                    | `NanoIpRoutePlanner`, `RotatingNanoIpRoutePlanner` |
| blockIndex          | string                                                | The information in which /64 block ips are chosen. This number increases on each ban. | `RotatingNanoIpRoutePlanner`                       |

#### IP Block Object

| Field | Type                            | Description              |
|-------|---------------------------------|--------------------------|
| type  | [IP Block Type](#ip-block-type) | The type of the ip block |
| size  | string                          | The size of the ip block |

#### IP Block Type

| IP Block Type  | Description         |
|----------------|---------------------|
| `Inet4Address` | The ipv4 block type |
| `Inet6Address` | The ipv6 block type |

#### Failing Address Object

| Field            | Type   | Description                                              |
|------------------|--------|----------------------------------------------------------|
| failingAddress   | string | The failing address                                      |
| failingTimestamp | int    | The timestamp when the address failed                    |
| failingTime      | string | The timestamp when the address failed as a pretty string |

---

### Get RoutePlanner status

```
GET /v4/routeplanner/status
```

Responses:

204 - No Content: This means the RoutePlanner is not enabled on the server.

200 - Content: This means that the RoutePlanner has been enabled, and will give the following information.

| Field   | Type                                        | Description                                                           |
|---------|---------------------------------------------|-----------------------------------------------------------------------|
| class   | ?[Route Planner Type](#route-planner-types) | The name of the RoutePlanner implementation being used by this server |
| details | ?[Details](#details-object) object          | The status details of the RoutePlanner                                |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "class": "RotatingNanoIpRoutePlanner",
  "details": {
    "ipBlock": {
      "type": "Inet6Address",
      "size": "1208925819614629174706176"
    },
    "failingAddresses": [
      {
        "failingAddress": "/1.0.0.0",
        "failingTimestamp": 1573520707545,
        "failingTime": "Mon Nov 11 20:05:07 EST 2019"
      }
    ],
    "blockIndex": "0",
    "currentAddressIndex": "36792023813"
  }
}
```

</details>

---

### Unmark a failed address

```
POST /v4/routeplanner/free/address
```

Request:

| Field   | Type   | Description                                                                 |
|---------|--------|-----------------------------------------------------------------------------|
| address | string | The address to unmark as failed. This address must be in the same ip block. |

<details markdown="1">
<summary>Example Payload</summary>

```json
{
  "address": "1.0.0.1"
}
```

</details>

Response:

204 - No Content

---

### Unmark all failed addresses

```
POST /v4/routeplanner/free/all
```

Response:

204 - No Content
