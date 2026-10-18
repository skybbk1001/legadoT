package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.data.entities.save

object SearchBookShelfHelp {

    data class AddResult(
        val total: Int,
        val added: Int,
        val skipped: Int,
    )

    fun addLoadedBooksToShelf(
        books: List<SearchBook>,
        isInBookshelf: (SearchBook) -> Boolean,
    ): AddResult {
        val pendingBooks = books.filterNot(isInBookshelf)
        if (pendingBooks.isEmpty()) {
            return AddResult(
                total = books.size,
                added = 0,
                skipped = books.size,
            )
        }

        var nextOrder = appDb.bookDao.minOrder - 1
        pendingBooks.forEach { searchBook ->
            val book = searchBook.toBook()
            prepareBookForShelf(book, nextOrder)
            nextOrder = book.order - 1
            book.save()
        }

        return AddResult(
            total = books.size,
            added = pendingBooks.size,
            skipped = books.size - pendingBooks.size,
        )
    }

    private fun prepareBookForShelf(book: Book, fallbackOrder: Int) {
        book.removeType(BookType.notShelf)
        if (book.order == 0) {
            book.order = fallbackOrder
        }
        appDb.bookDao.getBook(book.name, book.author)?.let { oldBook ->
            book.durChapterIndex = oldBook.durChapterIndex
            book.durChapterPos = oldBook.durChapterPos
            book.durChapterTitle = oldBook.durChapterTitle
        }
        if (ReadBook.book?.isSameNameAuthor(book) == true) {
            ReadBook.book = book
        } else if (AudioPlay.book?.isSameNameAuthor(book) == true) {
            AudioPlay.book = book
        }
    }
}
