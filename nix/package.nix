{ lib, stdenvNoCC, babashka-unwrapped, uberjar, datalevinPod, launcher, config }:
stdenvNoCC.mkDerivation {
  pname = "messenger-clj";
  version = "0.3.0";
  dontUnpack = true;
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    install -Dm644 ${uberjar}/share/messenger-clj/messenger-clj.jar "$out/share/messenger-clj/messenger-clj.jar"
    install -Dm644 ${config} "$out/share/messenger-clj/bb.edn"
    install -Dm755 ${launcher} "$out/bin/messenger-clj"
    substituteInPlace "$out/bin/messenger-clj" \
      --replace-fail '@babashka@' '${babashka-unwrapped}' \
      --replace-fail '@datalevinPod@' '${datalevinPod}' \
      --replace-fail '@out@' "$out"
    for command in send send-abrupt list register repair deregister rebind move retire heartbeat-state; do ln -s messenger-clj "$out/bin/hm-$command"; done
    runHook postInstall
  '';
  doInstallCheck = true;
  installCheckPhase = ''
    "$out/bin/messenger-clj" --help > help
    grep -F 'Usage: messenger-clj' help
    test "$(readlink "$out/bin/hm-send")" = messenger-clj
    "$out/bin/hm-repair" --help | grep -F 'repair'
    ! grep -R 'python\|hm.py' "$out/bin"
  '';
  meta = { description = "Typed Clojure CLI messenger with hm-* compatibility commands"; license = lib.licenses.epl20; platforms = [ "x86_64-linux" ]; mainProgram = "messenger-clj"; };
}
