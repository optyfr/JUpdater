package jupdater;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.HeadlessException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class JUpdater
{
	private final String name;
	private final String project;
	private JsonObject result = null;

	public static void main(final String[] args)
	{
		if(args.length == 0)
		{
			try
			{
				final Properties props = new Properties();
				props.load(JUpdater.class.getClassLoader().getResourceAsStream("install.properties"));
				final JUpdater updater = new JUpdater(props.getProperty("name"), props.getProperty("project"));
				updater.install(props.getProperty("archive"));
			}
			catch(final IOException e)
			{
				e.printStackTrace();
			}
		}
		else if(args.length == 2)
		{
			try
			{
				final JUpdater updater = new JUpdater(args[0], args[1]);
				updater.update();
			}
			catch(IOException | URISyntaxException e)
			{
				e.printStackTrace();
			}
		}
		else
			System.err.println("Bad arguments : " + Arrays.asList(args).stream().collect(Collectors.joining(", ")));
	}

	public JUpdater(final String name, final String project)
	{
		this.name = name;
		this.project = project;
		try
		{
			final URL url = new URL("https", "api.github.com", "/repos/" + this.name + "/" + this.project + "/releases/latest");
			result = Json.parse(new BufferedReader(new InputStreamReader(url.openStream(), Charset.forName("UTF-8")))).asObject();
		}
		catch(HeadlessException | IOException e)
		{
			e.printStackTrace();
		}
	}

	public void update() throws IOException, URISyntaxException
	{
		final URL url = getZipURL();
		if(url != null)
		{
			final Path filename = Paths.get(url.getPath()).getFileName();
			try (final InputStream in = new ProgressMonitorInputStream(null, "Downloading " + filename, url.openStream()))
			{
				final Path workdir = Paths.get("").toAbsolutePath();
				final Path dir = Paths.get(workdir.toString(), "updates");
				if(Files.notExists(dir))
					Files.createDirectory(dir);
				final Path file = dir.resolve(filename);
				Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
				unzip(file, workdir);
				launch(workdir);
			}
		}
	}

	public void install(final String archive) throws IOException
	{
		new JFileChooser()
		{
			private static final long serialVersionUID = 1L;
			{
				setDialogType(JFileChooser.SAVE_DIALOG);
				setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				setDialogTitle("Choose directory to install");
				if(showSaveDialog(null) == JFileChooser.APPROVE_OPTION)
				{
					final Path tempDir = Files.createTempDirectory("Install");
					final Path tempFile = tempDir.resolve(archive);
					Files.copy(JUpdater.class.getClassLoader().getResourceAsStream(archive), tempFile, StandardCopyOption.REPLACE_EXISTING);
					unzip(tempFile, getSelectedFile().toPath());
					tempFile.toFile().delete();
					tempDir.toFile().delete();
					launch(getSelectedFile().toPath());
				}
			}
		};
	}

	public void launch(final Path workdir) throws IOException
	{
		final String home = System.getProperty("java.home");
		final String os = System.getProperty("os.name");
		final String arch = System.getProperty("os.arch");
		final File java;
		if(os.toLowerCase().startsWith("windows"))
			java = new File(new File(home), "bin/javaw.exe");
		else
			java = new File(new File(home), "bin/java");
		new ProcessBuilder(java.getAbsolutePath(), "-jar", project + ".jar", arch.equals("x86")?(os.startsWith("Windows")?"-Xmx1g":"-Xmx1500m"):"-Xmx2g").directory(workdir.toFile()).start();
		System.exit(0);
	}

	public void unzip(final Path zipFile, final Path destDir) throws IOException
	{
		if(Files.notExists(destDir))
		{
			Files.createDirectories(destDir);
		}

		try (FileSystem zipFileSystem = FileSystems.newFileSystem(zipFile, null))
		{
			final Path root = zipFileSystem.getRootDirectories().iterator().next();

			Files.walkFileTree(root, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException
				{
					final Path destFile = Paths.get(destDir.toString(), file.toString());
					try
					{
						Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					}
					catch(final DirectoryNotEmptyException ignore)
					{
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException
				{
					final Path dirToCreate = Paths.get(destDir.toString(), dir.toString());
					if(Files.notExists(dirToCreate))
					{
						Files.createDirectory(dirToCreate);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	public String getChangeLog()
	{
		final StringBuffer buffer = new StringBuffer();
		try
		{
			final URL url = new URL("https", "api.github.com", "/repos/" + name + "/" + project + "/releases");
			final String current_version = getVersion();
			for(final JsonValue value : Json.parse(new BufferedReader(new InputStreamReader(url.openStream(), Charset.forName("UTF-8")))).asArray())
			{
				final JsonObject release = value.asObject();
				if(release.getString("tag_name", "").equals(current_version))
					break;
				String body = release.getString("body", "");
				final Parser parser = Parser.builder().build();
				final Node node = parser.parse(body);
				final HtmlRenderer renderer = HtmlRenderer.builder().build();
				body = renderer.render(node);
				buffer.append("<blockquote>").append("<h4><u>").append(release.getString("name", "")).append("</u></h4>").append(body).append("<br>").append("</blockquote>");
			}
		}
		catch(final IOException e)
		{
			e.printStackTrace();
		}
		return buffer.toString();
	}

	public boolean updateAvailable()
	{
		try
		{
			Files.deleteIfExists(Paths.get("JUpdater.tmp.jar"));
		}
		catch(IOException e)
		{
		}
		final String current_version = getVersion();
		final String new_version = result.getString("tag_name", "");
		if(current_version.isEmpty())
			return false;
		if(new_version.isEmpty())
			return false;
		return !current_version.equals(new_version);
	}

	public String getUpdateName()
	{
		return result.getString("name", result.getString("tag_name", ""));
	}

	public URL getZipURL()
	{
		for(final JsonValue asset : result.get("assets").asArray())
		{
			final JsonObject object = asset.asObject();
			if(object.getString("content_type", "").equals("application/x-zip-compressed"))
			{
				try
				{
					return new URL(object.getString("browser_download_url", null));
				}
				catch(final MalformedURLException e)
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
				final JLabel label = new JLabel();
				final Font font = label.getFont();
				final StringBuffer style = new StringBuffer();
				style.append("font-family:" + font.getFamily() + ";");

				setContentType("text/html");
				setText(String.format(
						"<html><body style=\"" + style
								+ "\"><h1>New JRomManager %s is available click <a href='javascript:update()' target='_blank'>HERE</a> to update</h1><h2>CHANGELOG</h2>%s</body></html>",
						getUpdateName(), getChangeLog()));
				addHyperlinkListener(e -> {
					if(e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED))
					{
						if("javascript:update()".equals(e.getDescription()))
						{
							try
							{
								final String home = System.getProperty("java.home");
								final String os = System.getProperty("os.name");
								final File java;
								if(os.toLowerCase().startsWith("windows"))
									java = new File(new File(home), "bin/javaw.exe");
								else
									java = new File(new File(home), "bin/java");
								final Path workdir = Paths.get("").toAbsolutePath();
								Files.copy(workdir.resolve("JUpdater.jar"), workdir.resolve("JUpdater.tmp.jar"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
								String[] args = {java.getAbsolutePath(), "-jar", "JUpdater.tmp.jar", name, project};
								new ProcessBuilder(args).directory(workdir.toFile()).start();
								System.exit(0);
							}
							catch(IOException e2)
							{
								e2.printStackTrace();
							}
						}
						else if(Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
						{
							try
							{
								Desktop.getDesktop().browse(e.getURL().toURI());
							}
							catch(IOException | URISyntaxException e1)
							{
								e1.printStackTrace();
							}
						}
					}
				});
				setEditable(false);
				setOpaque(false);
			}
		})
		{
			private static final long serialVersionUID = 1L;
			{
				setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
				setPreferredSize(new Dimension(getPreferredSize().width, 400));
				setBorder(new EmptyBorder(0, 0, 0, 0));
			}
		});
	}

	private String getVersion()
	{
		String version = ""; //$NON-NLS-1$
		final Package pkg = this.getClass().getPackage();
		if(pkg.getSpecificationVersion() != null)
			version += pkg.getSpecificationVersion(); // $NON-NLS-1$
		if(pkg.getImplementationVersion() != null)
			version += pkg.getImplementationVersion(); // $NON-NLS-1$
		return version;
	}

}
