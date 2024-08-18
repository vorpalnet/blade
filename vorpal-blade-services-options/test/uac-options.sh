#!/bin/bash

#export SERVICE=18005550002
export OCCAS=192.168.86.23

sipp -rsa ${OCCAS} -m 1 -sf uac-options.xml ${OCCAS}:5060
