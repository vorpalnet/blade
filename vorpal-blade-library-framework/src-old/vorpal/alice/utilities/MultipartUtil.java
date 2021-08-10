package vorpal.alice.utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import javax.activation.DataSource;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

@Deprecated
public class MultipartUtil {

	public static Multipart getMultipart(byte[] content, String contentType) throws MessagingException {
		DataSource ds = new ByteArrayDataSource(content, contentType);
		Multipart multipart = new MimeMultipart(ds);
		return multipart;
	}

	public static Multipart createMultipart() throws MessagingException {
		return new MimeMultipart("mixed");
	}

	public static String getBodyPart(Multipart multipart, String contentType) throws MessagingException, IOException {
		String body = null;

		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bp = multipart.getBodyPart(i);
			if (contentType.equalsIgnoreCase(bp.getContentType())) {
				BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream) bp.getContent()));
				StringBuilder strOut = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					strOut.append(line).append("\r\n");
				}
				body = strOut.toString();
				reader.close();
				break;
			}

		}
		return body;
	}

	public static void setBodyPart(Multipart multipart, byte[] content, String contentType) throws MessagingException {
		String disposition = null;

		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bp = multipart.getBodyPart(i);
			if (contentType.equalsIgnoreCase(bp.getContentType())) {
				disposition = bp.getDisposition();
				multipart.removeBodyPart(i);

				break;
			}
		}

		InternetHeaders ih1 = new InternetHeaders();
		ih1.setHeader("Content-Type", contentType);
		BodyPart bodyPart = new MimeBodyPart(ih1, content);
		if (disposition != null) {
			bodyPart.setDisposition(disposition);
		}

		multipart.addBodyPart(bodyPart);

	}

}
