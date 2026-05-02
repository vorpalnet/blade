/// Native SDP (RFC 4566) parser and model — no NIST/javax.sdp dependency.
///
/// [Sdp#parse(String)] reads SDP text into a model, [Sdp#toString()] writes it
/// back as canonical SDP. The model is Jackson-friendly so callers can also
/// route through a JSON tree (e.g. for JsonPath manipulation) without losing
/// fields.
///
/// Supported lines: `v=`, `o=`, `s=`, `i=`, `u=`, `e=`, `p=`, `c=`, `b=`,
/// `t=`, `r=`, `z=`, `k=`, `a=`, `m=` — at both session and media scope.
package org.vorpal.blade.framework.v2.sdp;
