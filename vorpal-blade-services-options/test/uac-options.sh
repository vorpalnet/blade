#!/bin/bash

#export SERVICE=18005550002
export OCCAS=172.16.32.118

sipp -rsa ${OCCAS} -m 1 -sf uac-options.xml ${OCCAS}:5060
