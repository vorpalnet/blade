#!/bin/bash

export SERVICE=2025550114
export DURATION=5000
export OCCAS=192.0.2.108

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf glare-uac.xml 192.0.2.108:5099
