package glacierHelper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;

/**
 * Creates a tar.gz archive of the specified directory without creating a
 * temporary file, and sends it through the pre-configured PipedOutputStream.
 *
 * Some portion of this code taken from: http://www.thoughtspark.org/node/53
 */
public class TarGzStream implements Runnable {
	private int bufferSize = 1024 * 1024;
	private boolean verbose = true;

	private String directoryPath;
	private PipedOutputStream pipeOut;
	private BufferedOutputStream bOut;
	private GzipCompressorOutputStream gzOut;
	private TarArchiveOutputStream tOut;

	/**
	 * @param root
	 *            directory for the desired archive.
	 */
	public TarGzStream(String directoryPath) {
		this.directoryPath = directoryPath;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	/**
	 * Initialize necessary streams
	 * 
	 * @return PipedOutputStream, through which the archive will be sent later on.
	 */
	public PipedOutputStream prepareOutputStream() {
		try {
			pipeOut = new PipedOutputStream();
			bOut = new BufferedOutputStream(pipeOut, bufferSize);
			gzOut = new GzipCompressorOutputStream(bOut);

			tOut = new TarArchiveOutputStream(gzOut);

			// LONGFILE_POSIX does not work well for long pathnames.
			// appears to be some problem with TarArchiveOutputStream.putArchiveEntry, where
			// it sets currBytes = 0 for directories??
			// tOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			tOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
			tOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		return pipeOut;
	}

	/**
	 * Sends the tar.gz archive through the output stream previously returned by the
	 * prepareOutputStream() call.
	 */
	public void startSendingData() {
		try {
			addFileToTarGz(tOut, directoryPath, "", directoryPath);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		} finally {
			try {
				tOut.finish();

				tOut.close();
				gzOut.close();
				bOut.close();

				pipeOut.flush();
				pipeOut.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
			if (verbose)
				System.out.println("tar.gz stream done");
		}
	}

	private void addFileToTarGz(TarArchiveOutputStream tOut, String path, String base, String rootDirectoryPath)
			throws IOException {
		File f = new File(path);
		String entryName = base + f.getName();
		TarArchiveEntry tarEntry = new TarArchiveEntry(f, entryName);

		if (f.isFile()) {
			if (verbose)
				System.out.println("adding file:" + path);

			tOut.putArchiveEntry(tarEntry);

			IOUtils.copy(new FileInputStream(f), tOut);

			tOut.closeArchiveEntry();
		} else if (Files.isSymbolicLink(f.toPath())) {

			Path absoluteRootPath = new File(rootDirectoryPath).toPath().toRealPath();

			Path target;

			try {

				Path absoluteTargetPath = f.toPath().toRealPath();

				if (!absoluteTargetPath.startsWith(absoluteRootPath)) {

					if (verbose)
						System.out.println("skipping symlink whose target is outside the archive:" + path + " -> "
								+ absoluteTargetPath);

					return;
				}

				target = absoluteRootPath.relativize(absoluteTargetPath);

			} catch (NoSuchFileException e) {

				if (verbose)
					System.out.println("skipping symlink whose target is missing:" + path + " -> " + e.getFile());

				return;
			}

			String targetString = target.toString();

			if (targetString.equals("")) {

				targetString = ".";
			}

			if (verbose)
				System.out.println("adding symlink:" + path + " -> " + targetString);

			tarEntry = new TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK);
			tarEntry.setLinkName(targetString);

			tOut.putArchiveEntry(tarEntry);
			tOut.closeArchiveEntry();
		} else {

			if (verbose)
				System.out.println("entering directory:" + path);

			tOut.putArchiveEntry(tarEntry);
			tOut.closeArchiveEntry();

			File[] children = f.listFiles();

			if (children != null) {
				for (File child : children) {
					addFileToTarGz(tOut, child.getAbsolutePath(), entryName + "/", rootDirectoryPath);
				}
			}
		}
	}

	/**
	 * Runnable implementation to process startSendingData() on a separate thread
	 */
	@Override
	public void run() {
		startSendingData();
	}
}
