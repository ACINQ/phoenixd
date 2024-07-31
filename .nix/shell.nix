{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = [
    pkgs.sqlite
    pkgs.curl
    pkgs.openjdk20
  
    pkgs.pkg-config
    pkgs.ncurses
    pkgs.stdenv.cc.cc.lib

    pkgs.git

    (pkgs.glibc.overrideAttrs (old: {
      version = "2.19";
    }))
  ];
}
