#!/bin/bash

export SERVICE=1983217114
export DURATION=5000
export OCCAS=172.16.32.108

sipp -d ${DURATION} -rsa ${OCCAS} -m 1 -s ${SERVICE} -sf glare-uac.xml 172.16.32.108:5099
