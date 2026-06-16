package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Diagram position of one state in the BLADE Flow editor.
///
/// Pure presentation metadata carried in [Diagram#getStates]: the AppRouter
/// never reads it, and deleting it costs nothing but the editor's saved
/// layout (states re-place by auto-layout on next open).
@JsonPropertyOrder({ "x", "y" })
public class Placement implements Serializable {
	private static final long serialVersionUID = 1L;

	private int x;
	private int y;

	public Placement() {
	}

	public Placement(int x, int y) {
		this.x = x;
		this.y = y;
	}

	@JsonPropertyDescription("Horizontal diagram position in pixels")
	public int getX() {
		return x;
	}

	public Placement setX(int x) {
		this.x = x;
		return this;
	}

	@JsonPropertyDescription("Vertical diagram position in pixels")
	public int getY() {
		return y;
	}

	public Placement setY(int y) {
		this.y = y;
		return this;
	}

}
