package vorpal.alice.utilities;

@Deprecated
public class CallStateException extends Exception {
	private static final long serialVersionUID = 1L;

	public CallStateException(String error) {
		super(error);
	}

}
