#!/bin/bash
export PW=`pwgen -Bs 10 1`
mkdir -p gen
echo $PW > gen/password