#!/bin/bash

export SERVICE=18165551234
export DURATION=5000
export OCCAS=occas

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf uac.xml ${OCCAS}:5099
