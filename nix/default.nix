{ pkgs, clj, root }:
let
  inherit (pkgs) lib;
  deps = { name = "messenger-clj-deps"; edn = root + "/bb.edn"; hash = "sha256-HNlkGVUe61CxFetUQ/bJwGuhAPAaTEgvk1FCgdpZ1MU="; };
  datalevinPod = pkgs.fetchzip { url = "https://github.com/juji-io/datalevin/releases/download/0.8.25/dtlv-0.8.25-ubuntu-latest-amd64.zip"; hash = "sha256-pS/F4UIWKdU/ftZR1hNXBsnI9TNRYXyjia2UKNJHU9k="; stripRoot = false; };
  source = paths: lib.fileset.toSource { inherit root; fileset = lib.fileset.unions (map (path: root + "/${path}") paths); };
  testSource = pkgs.callPackage ./test-source.nix { source = source [ "src" "test" "bin" ]; config = ./bb.edn; };
  orchestrateStub = pkgs.writeShellScriptBin "orchestrate" ''
    case "$1" in
      Lock.*) echo 'Locked.{ 1 Test sender [ /tmp ] test }' ;;
      Release.*) echo 'Released.{ 1 Test sender [ /tmp ] test }' ;;
      *) exit 1 ;;
    esac
  '';
  uberjar = clj.mkCljUberjar pkgs { name = "messenger-clj"; src = source [ "src" ]; main = "messenger-clj.main"; runtime = "bb"; inherit deps; };
in {
  package = pkgs.callPackage ./package.nix { inherit uberjar datalevinPod; launcher = ./launcher.sh; config = ./bb.edn; };
  tests = (clj.mkCljChecks pkgs {
    name = "messenger-clj";
    src = testSource;
    tests = [ "messenger-clj.core-test" "messenger-clj.public-test" "messenger-clj.cli-test" "messenger-clj.typed-store-test" "messenger-clj.legacy-import-test" ];
    runtime = "bb";
    inherit deps;
    nativeBuildInputs = [ orchestrateStub ];
    preCheck = ''
      export MESSENGER_CLJ_DATALEVIN_POD=${datalevinPod}/dtlv
      export MESSENGER_CLJ_CLASSPATH="$classpath"
      cd ${testSource}
    '';
  }).clj-tests;
}
