package okay.desk.bench

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.{Files, Path}
import okay.desk.statement.{Reader, Span}

/**
 * THE STATES OF A CORE, KEPT ON DISK (docs/toolkit.md): what a core gives for a text — its tokens' places and the
 * raw states — written once, read back by every later run over the same core (another seed, other heads, a
 * measure): the model is not run again and nothing is held in memory. Exact: floats as they were. A directory is
 * one core as it reads (base, adapter, echo) — the caller names it so; a text's file is its hash.
 */
final class StateStore(dir: Path):
  Files.createDirectories(dir)
  private def file(text: String): Path =
    val h = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8")).map(b => f"$b%02x").mkString
    dir.resolve(h.take(2)).resolve(h + ".states")
  /** the raw reading of `text`: from the disk, else by `read` (its states un-normalized) and kept */
  def seen(text: String, read: => Reader.Seen): Reader.Seen =
    val f = file(text)
    if Files.exists(f) then load(f, text)
    else
      val s = read
      Files.createDirectories(f.getParent)
      val tmp = f.resolveSibling(f.getFileName.toString + ".tmp")
      val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))
      try
        out.writeInt(s.tokens.size)
        s.tokens.foreach { t => out.writeInt(t.from); out.writeInt(t.until) }
        out.writeInt(if s.states.isEmpty then 0 else s.states.head.length)
        s.states.foreach(_.foreach(out.writeFloat))
      finally out.close()
      Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
      s
  private def load(f: Path, text: String): Reader.Seen =
    val in = DataInputStream(BufferedInputStream(Files.newInputStream(f), 1 << 16))
    try
      val n = in.readInt()
      val tokens = Vector.fill(n)(Span(in.readInt(), in.readInt()))
      val d = in.readInt()
      val states = Vector.fill(n)(Array.fill(d)(in.readFloat()))
      Reader.Seen(text, tokens, states)
    finally in.close()
