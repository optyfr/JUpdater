package jupdater;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.HeadlessException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;

import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class JUpdater
{
	private String name;
	private String project;
	private JsonObject result = null;

	public JUpdater(String name, String project)
	{
		this.name = name;
		this.project = project;
		try
		{
			URL url = new URL("https", "api.github.com", "/repos/"+this.name+"/"+this.project+"/releases/latest");
			result = Json.parse(new BufferedReader(new InputStreamReader(url.openStream(), Charset.forName("UTF-8")))).asObject();
		}
		catch (HeadlessException | IOException e)
		{
			e.printStackTrace();
		}
	}

	public String getChangeLog()
	{
		StringBuffer buffer = new StringBuffer();
		try
		{
			URL url = new URL("https", "api.github.com", "/repos/"+this.name+"/"+this.project+"/releases");
			String current_version = getVersion();
			for (JsonValue value : Json.parse(new BufferedReader(new InputStreamReader(url.openStream(), Charset.forName("UTF-8")))).asArray())
			{
				JsonObject release = value.asObject();
				if (release.getString("tag_name", "").equals(current_version))
					break;
				String body = release.getString("body", "");
				Parser parser = Parser.builder().build();
				Node node = parser.parse(body);
				HtmlRenderer renderer = HtmlRenderer.builder().build();
				body  = renderer.render(node);
				buffer.append("<blockquote>").append("<h4><u>").append(release.getString("name", "")).append("</u></h4>").append(body).append("<br>").append("</blockquote>");
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		return buffer.toString();
	}

	public boolean updateAvailable()
	{
		String current_version = getVersion();
		String new_version = result.getString("tag_name", "");
		if (current_version.isEmpty())
			return false;
		if (new_version.isEmpty())
			return false;
		return !current_version.equals(new_version);
	}

	public String getUpdateName()
	{
		return result.getString("name", result.getString("tag_name", ""));
	}

	public URL getZipURL()
	{
		for (JsonValue asset : result.get("assets").asArray())
		{
			JsonObject object = asset.asObject();
			if (object.getString("content_type", "").equals("application/x-zip-compressed"))
			{
				try
				{
					return new URL(object.getString("browser_download_url", null));
				}
				catch (MalformedURLException e)
				{
					return null;
				}
			}
		}
		return null;
	}

	public void showMessage()
	{
		JOptionPane.showMessageDialog(null, new JScrollPane(new JEditorPane()
		{
			private static final long serialVersionUID = 1L;
			{
				JLabel label = new JLabel();
				Font font = label.getFont();
				StringBuffer style = new StringBuffer();
				style.append("font-family:" + font.getFamily() + ";");

				this.setContentType("text/html");
				this.setText(String.format("<html><body style=\"" + style + "\"><h1>New JRomManager %s is available <a href='%s' target='_blank'>HERE</a></h1><h2>CHANGELOG</h2>%s</body></html>", getUpdateName(), getZipURL().toExternalForm(), getChangeLog()));
				this.addHyperlinkListener(new HyperlinkListener()
				{
					@Override
					public void hyperlinkUpdate(HyperlinkEvent e)
					{
						if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED))
						{
							if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
							{
								try
								{
									Desktop.getDesktop().browse(e.getURL().toURI());
								}
								catch (IOException | URISyntaxException e1)
								{
									e1.printStackTrace();
								}
							}
						}
					}
				});
				this.setEditable(false);
				this.setOpaque(false);
			}
		})
		{
			private static final long serialVersionUID = 1L;
			{
				this.setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_ALWAYS);
				this.setPreferredSize(new Dimension(getPreferredSize().width, 400));
				this.setBorder(new EmptyBorder(0, 0, 0, 0));
			}
		});
	}

	private String getVersion()
	{
		String version = ""; //$NON-NLS-1$
		final Package pkg = this.getClass().getPackage();
		if (pkg.getSpecificationVersion() != null)
			version += pkg.getSpecificationVersion(); // $NON-NLS-1$
		if (pkg.getImplementationVersion() != null)
			version += pkg.getImplementationVersion(); // $NON-NLS-1$
		return version;
	}

}
