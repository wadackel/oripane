{
  description = "Oripane — a Mac display on an Android phone";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # Gradle comes from the wrapper in android/; a second copy on PATH invites builds that
          # silently run a different version.
          packages = [ pkgs.jdk17 pkgs.bun pkgs.yq-go ];
          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17}"
            export ANDROID_HOME="''${ANDROID_HOME:-$HOME/Library/Android/sdk}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
          '';
        };
      });
    };
}
