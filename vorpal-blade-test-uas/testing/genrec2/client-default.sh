#!/bin/bash

export SERVICE=01983217113
export DURATION=5000
export OCCAS=192.168.1.107

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf uac.xml 192.168.1.107:5099
