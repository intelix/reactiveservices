#!/bin/bash
export PW=`pwgen -Bs 10 1`
mkdir -p $1
echo $PW > $1/password