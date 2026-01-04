#!/bin/bash

export SERVICE=18165551234
export DURATION=5000
export OCCAS=192.168.1.160
export UAS=192.168.1.107:5099

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf uac.xml ${UAS}
