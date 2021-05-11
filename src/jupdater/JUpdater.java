package jupdater;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.HeadlessException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.ProgressMonitorInputStream;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class JUpdater
{
	private static final String TAG_NAME = "tag_name";
	private static final String TMP_JAR = "JUpdater.tmp.jar";
	private final String name;
	private final String project;
	private JsonObject result = null;

	public static void main(final String[] args)
	{
		if (args.length == 0)
		{
			try
			{
				final var props = new Properties();
				props.load(JUpdater.class.getClassLoader().getResourceAsStream("install.properties"));
				final var updater = new JUpdater(props.getProperty("name"), props.getProperty("project"));
				updater.install(props.getProperty("archive"));
			}
			catch (final IOException e)
			{
				e.printStackTrace();
			}
		}
		else if (args.length == 2)
		{
			try
			{
				final var updater = new JUpdater(args[0], args[1]);
				updater.update();
			}
			catch (IOException e)
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
			final var url = new URL("https", "api.github.com", "/repos/" + this.name + "/" + this.project + "/releases/latest");
			try (final var in = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)))
			{
				result = Json.parse(in).asObject();
			}
		}
		catch (HeadlessException | IOException e)
		{
			e.printStackTrace();
		}
	}

	public void update() throws IOException
	{
		final var url = getZipURL();
		if (url != null)
		{
			final Path filename = Paths.get(url.getPath()).getFileName();
			try (final InputStream in = new ProgressMonitorInputStream(null, "Downloading " + filename, url.openStream()))
			{
				final var workdir = Paths.get("").toAbsolutePath();
				final var dir = Paths.get(workdir.toString(), "updates");
				if (Files.notExists(dir))
					Files.createDirectory(dir);
				final var file = dir.resolve(filename);
				Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
				unzip(file, workdir);
				launch(workdir);
			}
		}
	}

	public void install(final String archive) throws IOException
	{
		final var chooser = new JFileChooser();
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose directory to install");
		if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION)
		{
			final Path tempDir = Files.createTempDirectory("Install");
			final Path tempFile = tempDir.resolve(archive);
			Files.copy(JUpdater.class.getClassLoader().getResourceAsStream(archive), tempFile, StandardCopyOption.REPLACE_EXISTING);
			unzip(tempFile, chooser.getSelectedFile().toPath());
			Files.delete(tempFile);
			Files.delete(tempDir);
			launch(chooser.getSelectedFile().toPath());
		}
	}

	public void launch(final Path workdir) throws IOException
	{
		final String home = System.getProperty("java.home");
		final String os = System.getProperty("os.name");
		final String arch = System.getProperty("os.arch");
		final File java;
		if (os.toLowerCase().startsWith("windows"))
			java = new File(new File(home), "bin/javaw.exe");
		else
			java = new File(new File(home), "bin/java");
		final String mem;
		if (arch.equals("x86"))
		{
			if (os.startsWith("Windows"))
				mem = "-Xmx1g";
			else
				mem = "-Xmx1500m";
		}
		else
			mem = "-Xmx2g";
		new ProcessBuilder(java.getAbsolutePath(), "-jar", project + ".jar", mem).directory(workdir.toFile()).start();
		System.exit(0);
	}

	public void unzip(final Path zipFile, final Path destDir) throws IOException
	{
		if (Files.notExists(destDir))
		{
			Files.createDirectories(destDir);
		}

		try (final var zipFileSystem = FileSystems.newFileSystem(zipFile, (ClassLoader) null))
		{
			final Path root = zipFileSystem.getRootDirectories().iterator().next();

			Files.walkFileTree(root, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException
				{
					final var destFile = Paths.get(destDir.toString(), file.toString());
					try
					{
						Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					}
					catch (final DirectoryNotEmptyException ignore)
					{
						// ignore
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException
				{
					final var dirToCreate = Paths.get(destDir.toString(), dir.toString());
					if (Files.notExists(dirToCreate))
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
		final var buffer = new StringBuilder();
		try
		{
			final var url = new URL("https", "api.github.com", "/repos/" + name + "/" + project + "/releases");
			final var current_version = getVersion();
			for (final JsonValue value : Json.parse(new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))).asArray())
			{
				final JsonObject release = value.asObject();
				if (release.getString(TAG_NAME, "").equals(current_version))
					break;
				var body = release.getString("body", "");
				final var parser = Parser.builder().build();
				final var node = parser.parse(body);
				final HtmlRenderer renderer = HtmlRenderer.builder().build();
				body = renderer.render(node);
				buffer.append("<blockquote>").append("<h4><u>").append(release.getString("name", "")).append("</u></h4>").append(body).append("<br>").append("</blockquote>");
			}
		}
		catch (final IOException e)
		{
			e.printStackTrace();
		}
		return buffer.toString();
	}

	public boolean updateAvailable()
	{
		if (result == null)
			return false;
		try
		{
			Files.deleteIfExists(Paths.get(TMP_JAR));
		}
		catch (IOException e)
		{
			// ignore
		}
		final var current_version = getVersion();
		final var new_version = result.getString(TAG_NAME, "");
		if (current_version.isEmpty())
			return false;
		if (new_version.isEmpty())
			return false;
		return !current_version.equals(new_version);
	}

	public String getUpdateName()
	{
		return result.getString("name", result.getString(TAG_NAME, ""));
	}

	public URL getZipURL()
	{
		for (final var asset : result.get("assets").asArray())
		{
			final var object = asset.asObject();
			if (object.getString("content_type", "").equals("application/x-zip-compressed"))
			{
				try
				{
					return new URL(object.getString("browser_download_url", null));
				}
				catch (final MalformedURLException e)
				{
					return null;
				}
			}
		}
		return null;
	}

	public void showMessage()
	{
		final var editor = new JEditorPane();
		final var label = new JLabel();
		final var font = label.getFont();
		final var style = new StringBuilder();
		style.append("font-family:" + font.getFamily() + ";");

		editor.setContentType("text/html");
		editor.setText(String.format("<html><body style=\"%s\"><h1>New JRomManager %s is available click <a href='javascript:update()' target='_blank'>HERE</a> to update</h1><h2>CHANGELOG</h2>%s</body></html>", style, getUpdateName(), getChangeLog()));
		editor.addHyperlinkListener(this::hyperlinkClicked);
		editor.setEditable(false);
		editor.setOpaque(false);

		final var scrollpane = new JScrollPane(editor);
		scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollpane.setPreferredSize(new Dimension(scrollpane.getPreferredSize().width, 400));
		scrollpane.setBorder(new EmptyBorder(0, 0, 0, 0));

		JOptionPane.showMessageDialog(null, scrollpane);
	}

	/**
	 * @param e
	 */
	private void hyperlinkClicked(HyperlinkEvent e)
	{
		if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED))
		{
			if ("javascript:update()".equals(e.getDescription()))
			{
				try
				{
					final String home = System.getProperty("java.home");
					final String os = System.getProperty("os.name");
					final File java;
					if (os.toLowerCase().startsWith("windows"))
						java = new File(new File(home), "bin/javaw.exe");
					else
						java = new File(new File(home), "bin/java");
					final var workdir = Paths.get("").toAbsolutePath();
					Files.copy(workdir.resolve("JUpdater.jar"), workdir.resolve(TMP_JAR), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					final var args = new String[] { java.getAbsolutePath(), "-jar", TMP_JAR, name, project };
					new ProcessBuilder(args).directory(workdir.toFile()).start();
					System.exit(0);
				}
				catch (IOException e2)
				{
					e2.printStackTrace();
				}
			}
			else if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
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

	private String getVersion()
	{
		var version = new StringBuilder(); // $NON-NLS-1$
		final var pkg = this.getClass().getPackage();
		if (pkg.getSpecificationVersion() != null)
			version.append(pkg.getSpecificationVersion()); // $NON-NLS-1$
		if (pkg.getImplementationVersion() != null)
		{
			String patch = pkg.getImplementationVersion(); // $NON-NLS-1$
			if (patch.charAt(0) != 'b')
				version.append('.');
			version.append(patch);
		}
		return version.toString();
	}

}
