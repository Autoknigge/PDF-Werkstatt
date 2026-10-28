package de.autoknigge.pdfwerkstatt

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.concurrent.Executors

/** Seitenliste mit Vorschau: verschieben (Pfeile oder lange drücken und ziehen), drehen, entfernen. */
class PageAdapter(
    private val items: MutableList<PageItem>,
    private val sources: Map<Long, PdfSource>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    // PdfRenderer ist nicht threadsicher: alle Vorschaubilder auf einem Thread.
    private val loader = Executors.newSingleThreadExecutor()
    private val renderers = HashMap<Long, PageRenderer>()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.tvNum)
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val up: View = v.findViewById(R.id.btnUp)
        val down: View = v.findViewById(R.id.btnDown)
        val rotate: View = v.findViewById(R.id.btnRotate)
        val delete: View = v.findViewById(R.id.btnDelete)
    }

    init {
        setHasStableIds(true)
    }

    val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
            Collections.swap(items, a, b)
            notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            notifyItemRangeChanged(0, items.size) // Seitennummern neu
        }
    })

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val src = sources[item.sourceId]
        h.num.text = (position + 1).toString()
        var info = "Seite ${item.index + 1}"
        if (sources.size > 1 && src != null) info += " aus\n${src.name}"
        if (item.rotation % 360 != 0) info += "\ngedreht ${item.rotation % 360}°"
        h.info.text = info
        h.thumb.rotation = item.rotation.toFloat()
        h.thumb.tag = item.id

        val cached = cache.get(item.id)
        if (cached != null) {
            h.thumb.setImageBitmap(cached)
        } else {
            h.thumb.setImageBitmap(null)
            if (src != null) loadThumb(h, item, src)
        }

        h.up.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p > 0) swap(p, p - 1)
        }
        h.down.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < items.size - 1) swap(p, p + 1)
        }
        h.rotate.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                items[p].rotation = (items[p].rotation + 90) % 360
                notifyItemChanged(p)
            }
        }
        h.delete.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                items.removeAt(p)
                notifyItemRemoved(p)
                notifyItemRangeChanged(p, items.size - p)
                onChanged()
            }
        }
        h.itemView.setOnLongClickListener {
            touchHelper.startDrag(h)
            true
        }
    }

    private fun loadThumb(h: VH, item: PageItem, src: PdfSource) {
        loader.execute {
            val bmp = try {
                val r = renderers.getOrPut(src.id) { PageRenderer(src.file) }
                r.render(item.index, 220)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null) {
                cache.put(item.id, bmp)
                main.post {
                    if (h.thumb.tag == item.id) h.thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun swap(a: Int, b: Int) {
        Collections.swap(items, a, b)
        notifyItemMoved(a, b)
        notifyItemChanged(a)
        notifyItemChanged(b)
    }

    fun shutdown() {
        loader.execute {
            renderers.values.forEach {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
            renderers.clear()
        }
        loader.shutdown()
    }
}
