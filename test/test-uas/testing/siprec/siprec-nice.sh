#!/bin/bash

export SERVICE=18005550002
export DURATION=5000
export OCCAS=192.0.2.107

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf uac.xml 192.0.2.107:5099
