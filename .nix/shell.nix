{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = [
    pkgs.sqlite
    pkgs.curl
    pkgs.openjdk17

    (pkgs.glibc.overrideAttrs (old: {
      version = "2.19";
    }))
  ];
}
