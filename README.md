# hyproxy

a [velocity](https://github.com/PaperMC/Velocity)-like proxy for hytale

## proxy setup

to setup hyproxy, you can download [the proxy jar from the latest release](https://github.com/xyzeva/hyproxy/releases/latest) (or [compile it yourself](#how-to-compile)) and run it with java
```sh
java -jar hyproxy.jar
```

on initial startup, hyproxy will prompt you to authenticate with hytale, after first authentication a file in the current working directory called `.hyproxy-oauth-session.json` that stores your session. **you should not give this file to anyone as it will give them access to your hytale account**

after that, adjust the config (`config.toml`)'s backend ips and the `public-ip` to your proxies public ip address (domain, or direct public ip).

add your own profile id (which you can obtain by connecting to your proxy, it will show up in your logs) to the `permissions` in the `config.toml` with the permission `op`. this will give you access to every permission on the proxy.

**you migh notice you cannot connect to your backends right now, to fix that, please follow [this section](#backend-setup)**

## backend setup

to set up your backend instance to be able to be connected from hyproxy, you have to download [the backend plugin from the latest release](https://github.com/xyzeva/hyproxy/releases/latest) (or [compile it yourself](#how-to-compile)) and put it in your servers mods folder.

after that, you need to grab the file called `proxy.secret` on the proxy's contents and put them into the `ProxySecret` field in `mods/xyzeva_HyProxyBackend/config.json` (will appear on first startup with the plugin)

and then you need to add the following launch flags *after* the server jar
```
--auth-mode insecure
```

**only do this if you have the plugin installed, doing this without the plugin installed will open you upto SERIOUS security issues**

after that, set your backend ip in the proxy config and restart both the proxy and the backend. you now can connect!

## how to compile

to compile the proxy, you can simply run
```sh
./gradlew :proxy:build
```

the built jar will be at `proxy/build/libs/hyproxy-{version}.jar`

to compile the backend plugin, its a bit more complicated.

first, you have to get the HytaleServer jar from your hytale game installation and put it in `backend-plugin/libs/HytaleServer.jar`

and then you can run
```
./gradlew :backend-plugin:build
```

and the built jar will be at `backend-plugin/build/libs/hyproxy-backend-{version}.jar`

## future plans

i plan to implement:
- plugins (already-semi sort of there)
- backend sets (load balancing)

contributions to this project are very welcome! feel free to work on anything you want and PR it in