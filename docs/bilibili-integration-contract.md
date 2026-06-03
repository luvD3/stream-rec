# Bilibili Integration Contract

This document fixes the cross-repository contract for the Bilibili integration.

## Platform identity

- Backend enum name: `BILIBILI`
- Frontend platform key: `bilibili`
- Supported URL shape: `https://live.bilibili.com/{roomId}`

## Configuration

Bilibili v1 does not expose a quality selector. The recorder requests the
highest available stream and selects the highest actual quality returned by the
platform.

Supported Bilibili-specific fields:

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

## Out of scope

- Bilibili danmu recording
- Browser Cookie auto-import
- Embedded Bilibili login WebView
- User-selectable Bilibili quality
