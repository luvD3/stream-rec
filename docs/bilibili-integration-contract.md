# Bilibili Integration Contract

This document fixes the cross-repository contract for the Bilibili integration.

## Platform identity

- Backend enum name: `BILIBILI`
- Frontend platform key: `bilibili`
- Supported URL shape: `https://live.bilibili.com/{roomId}`

## Configuration

Bilibili v1 supports recording and plain text danmu download for public live
rooms. Streamer-level danmu uses the shared `downloadConfig.danmu` field.

Supported Bilibili-specific fields:

- `quality`: optional preferred quality; the recorder selects the highest
  actual stream at or below the selected quality
- `sourceFormat`: optional stream format preference, default `flv`
- `cookies`: optional manual Cookie string

The platform also uses the shared platform timing fields:

- `downloadCheckInterval`
- `fetchDelay`
- `partedDownloadRetry`

## Cookie verification

Cookie verification is read-only. The verification endpoint accepts a Cookie
string and optional Bilibili room URL, checks Bilibili live APIs, and returns
whether the Cookie appears usable plus the highest actual quality currently
available. It must not persist Cookie data.

## Danmu recording

Danmu recording is supported through the same shared download setting used by
other platforms. The frontend must submit `downloadConfig.danmu` with Bilibili
streamers when a user enables the shared danmu switch.

## Out of scope

- Browser Cookie auto-import
- Embedded Bilibili login WebView
- Non-text Bilibili events such as gifts, Super Chat, and guard events
