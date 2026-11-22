# SIM Assigner

Bulk-sets which SIM the dialler uses for each contact, driven by Google Contacts labels.

It writes Android's built-in per-number fields (`preferred_phone_account_component_name` /
`preferred_phone_account_id`, API 28+) on every phone number of the matched contacts.

## Install
Every push to `main` builds the APK through GitHub Actions and attaches it to the **Latest build**
release. Open the release on the phone, download `SimAssigner.apk` and allow installs from that app
when Android asks.

## Screens
- **SIMs**: the calling accounts on the phone and the exact values the app writes.
- **Inspect / Test**: search a contact, set or clear a SIM on just that contact, and use
  *Snapshot → change Preferred SIM in Samsung Contacts → Compare* to show where Samsung stores its
  own setting. *Copy* puts the result on the clipboard.
- **Bulk by label**: choose a SIM per label, then *Preview* and *Apply*. Contacts that sit in two
  labels set to different SIMs are skipped and listed. A JSON backup is written before every Apply,
  and *Restore last backup* undoes it.

## Samsung note
Samsung's Phone app may keep its own Preferred SIM setting outside these standard fields. Run the
Inspect/Test checks on one contact before any bulk run.
