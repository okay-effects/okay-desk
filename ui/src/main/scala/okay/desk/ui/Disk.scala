package okay.desk.ui

import okay.business.scala3.*

/**
 * WHAT THE PAGE KEEPS ON DISK: the text of every file opened, by the table's name — so the tables are there again
 * at the next visit, and nothing is fetched twice. A page answers it by the browser's Cache API (its storage on
 * disk); a test by a map. (The model's files are cached by the page's own fetch, under the same roof.)
 */
enum Disk[X]:
  case Put(name: String, text: String) extends Disk[Unit]
  case Get(name: String) extends Disk[Option[String]]
  case Names() extends Disk[List[String]]
  case Remove(name: String) extends Disk[Unit]

object Disk:
  def put[R[_]](name: String, text: String)(using In[Disk, R]): Unit ! R = perform[Disk](Put(name, text))
  def get[R[_]](name: String)(using In[Disk, R]): Option[String] ! R = perform[Disk](Get(name))
  def names[R[_]](using In[Disk, R]): List[String] ! R = perform[Disk](Names())
  def remove[R[_]](name: String)(using In[Disk, R]): Unit ! R = perform[Disk](Remove(name))

  /** kept in a map: a test's disk */
  def inMemory[R[_], Rest[_], A](kept: scala.collection.mutable.Map[String, String])(p: A ! R)(
      using Split[Disk, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Disk, Rest, A, A](a => pure(a), [X] => (asked: Disk[X]) => asked match
      case Put(name, text) => kept(name) = text
      case Get(name) => kept.get(name)
      case Names() => kept.keys.toList.sorted
      case Remove(name) => kept.remove(name); ()))
