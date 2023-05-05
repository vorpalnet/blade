package org.vorpal.blade.framework.logging;

import java.util.logging.Level;

public class LogParameters {

	// System.getProperty()

	protected Boolean useParent = null;
	protected String name = null;
	protected String directory = null;
	protected Integer limit = null;
	protected Integer count = null;
	protected Boolean append = null;
	protected Level level = null;

	public Boolean getUseParent() {
		return useParent;
	}

	public void setUseParent(Boolean useParent) {
		this.useParent = useParent;
	}

	public String getDirectory() {
		return directory;
	}

	public void setDirectory(String directory) {
		this.directory = directory;
	}

	public Integer getLimit() {
		return limit;
	}

	public void setLimit(Integer limit) {
		this.limit = limit;
	}

	public Integer getCount() {
		return count;
	}

	public void setCount(Integer count) {
		this.count = count;
	}

	public Boolean getAppend() {
		return append;
	}

	public void setAppend(Boolean append) {
		this.append = append;
	}

	public Level getLevel() {
		return level;
	}

	public void setLevel(Level level) {
		this.level = level;
	}

}
