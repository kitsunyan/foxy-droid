# Foxy Droid

Yet another F-Droid client.

[![Release](https://img.shields.io/github/v/release/kitsunyan/foxy-droid)](https://github.com/kitsunyan/foxy-droid/releases)
[![F-Droid](https://img.shields.io/f-droid/v/nya.kitsunyan.foxydroid)](https://f-droid.org/packages/nya.kitsunyan.foxydroid/)

## Description

Unofficial F-Droid client that resembles the classic F-Droid client.

The client supports all basic F-Droid features, such as repository management and package installation. It doesn't
support privileged extension, root installation, or local repositories.

### Features

* Classic F-Droid style, without cards or inappropriate animations
* Fast repositories synchronization under good network conditions
* Built upon standard Android components and a minimal set of dependencies
* Made with attention to details, both in program logic and design

### Screenshots

<p>
<img src="metadata/en-US/images/phoneScreenshots/1.png" width="16%" />
<img src="metadata/en-US/images/phoneScreenshots/2.png" width="16%" />
<img src="metadata/en-US/images/phoneScreenshots/3.png" width="16%" />
<img src="metadata/en-US/images/phoneScreenshots/4.png" width="16%" />
<img src="metadata/en-US/images/phoneScreenshots/5.png" width="16%" />
<img src="metadata/en-US/images/phoneScreenshots/6.png" width="16%" />
</p>

## Building and Installing

Android SDK path should be specified either using `ANDROID_HOME` environment variable or using `sdk.dir` property in
`local.properties`.

Signing can be done automatically using `keystore.properties` as follows:

```properties
store.file=/path/to/keystore
store.password=key-store-password
key.alias=key-alias
key.password=key-password
```

Run `./gradlew assembleRelease` to build the package. The package can be installed using Android package manager.

## License

Foxy Droid is available under the terms of GNU General Public License v3 or later. Copyright (C) 2020 kitsunyan.
