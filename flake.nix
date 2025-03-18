{
  description = "Server equivalent of the popular Phoenix wallet";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ ];
        };

        # Importing glibc 2.19 for compiling the recent version of curl
        pkgs-glibc = import
          (builtins.fetchTarball {
            url = "https://github.com/NixOS/nixpkgs/archive/b6f505c60a2417d4fad4dc5245754e4e33eb4d40.tar.gz";
            sha256 = "sha256:0hhb8sar8qxi179d6c5h6n8f7nm71xxqqbynjv8pldvpsmsxxzh9";
          })
          { inherit system; };
      in
      {
        packages = {
          default = pkgs.gnumake;
        };
        formatter = pkgs.nixpkgs-fmt;

        devShell = pkgs.mkShellNoCC {
          buildInputs = with pkgs; [
            # build dependencies
            git
            pkg-config
            zlib
            wget

            sqlite
            jdk
            gradle
            kotlin

            # Ok this should help you lean a few things
            # See https://github.com/ACINQ/phoenixd/issues/1#issuecomment-2018205685
            (pkgs.stdenv.mkDerivation {
              name = "curl-7.87.0";
              src = pkgs.fetchurl {
                url = "https://curl.se/download/curl-7.87.0.tar.bz2";
                sha256 = "sha256-XW4Sh2G3EQlG0Sdq/28PJm8rcm9eYZ9+CgV6R0FV8wc=";
              };
              buildInputs = [ pkgs.zlib pkgs.openssl.dev ];
              configureFlags = [ "--with-zlib=${pkgs.zlib}" "--with-openssl=${pkgs.openssl.dev}" ];
            })

            ncurses
          ] ++ [
            pkgs-glibc.glibc
          ];

          shellHook = ''
            # FIXME: this need to go in a build task
            gradle linuxX64DistZip
          '';
        };
      }
    );
}
