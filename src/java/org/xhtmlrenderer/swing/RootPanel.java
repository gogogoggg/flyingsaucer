package org.xhtmlrenderer.swing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xhtmlrenderer.event.DocumentListener;
import org.xhtmlrenderer.extend.NamespaceHandler;
import org.xhtmlrenderer.extend.RenderingContext;
import org.xhtmlrenderer.extend.UserInterface;
import org.xhtmlrenderer.layout.*;
import org.xhtmlrenderer.layout.content.DomToplevelNode;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.render.ReflowEvent;
import org.xhtmlrenderer.render.RenderQueue;
import org.xhtmlrenderer.util.Uu;
import org.xhtmlrenderer.util.XRLog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;


public class RootPanel extends JPanel implements ComponentListener, UserInterface {


    /**
     * Description of the Field
     */
    protected Dimension intrinsic_size;

    /* can we figure out how to get rid of this?
    */
    protected BoxHolder bh;

    /**
     * Description of the Field
     */
    protected Map documentListeners;


    /**
     * Gets the context attribute of the BasicPanel object
     *
     * @return The context value
     */
    public SharedContext getContext() {
        return getRenderingContext().getContext();
    }

    /**
     * Description of the Field
     */
    protected RenderingContext ctx;

    /**
     * Gets the renderingContext attribute of the BasicPanel object
     *
     * @return The renderingContext value
     */
    public RenderingContext getRenderingContext() {
        return ctx;
    }

    protected Context layout_context;


    /**
     * Description of the Field
     */
    protected Box body_box = null;

    private Thread layoutThread;
    private Thread renderThread;

    /**
     * Sets the document attribute of the BasicPanel object
     *
     * @param doc The new document value
     * @param url The new document value
     * @param nsh The new document value
     */
    public void setDocument(Document doc, String url, NamespaceHandler nsh) {
        resetScrollPosition();
        this.doc = doc;

        //have to do this first
        getRenderingContext().setBaseURL(url);
        getContext().setNamespaceHandler(nsh);
        getRenderingContext().getStyleReference().setDocumentContext(getContext(), getContext().getNamespaceHandler(), doc, this);

        RenderQueue.getInstance().dispatchLayoutEvent(new ReflowEvent(ReflowEvent.DOCUMENT_SET));
        //calcLayout();
        repaint();
    }


    /**
     * Description of the Field
     */
    protected JScrollPane enclosingScrollPane;

    /**
     * Description of the Method
     */
    public void resetScrollPosition() {
        if (this.enclosingScrollPane != null) {
            this.enclosingScrollPane.getVerticalScrollBar().setValue(0);
        }
    }

    /**
     * The method is invoked by {@link #addNotify} and {@link #removeNotify} to
     * ensure that any enclosing {@link JScrollPane} works correctly with this
     * panel. This method can be safely invoked with a <tt>null</tt> scrollPane.
     *
     * @param scrollPane the enclosing {@link JScrollPane} or <tt>null</tt> if
     *                   the panel is no longer enclosed in a {@link JScrollPane}.
     */
    protected void setEnclosingScrollPane(JScrollPane scrollPane) {
        // if a scrollpane is already installed we remove it.
        if (enclosingScrollPane != null) {
            enclosingScrollPane.removeComponentListener(this);
        }

        enclosingScrollPane = scrollPane;

        if (enclosingScrollPane != null) {
            Uu.p("added root panel as a component listener to the scroll pane");
            enclosingScrollPane.addComponentListener(this);
        }
    }

    /**
     * Gets the fixedRectangle attribute of the BasicPanel object
     *
     * @return The fixedRectangle value
     */
    public Rectangle getFixedRectangle() {
        if (enclosingScrollPane != null) {
            return enclosingScrollPane.getViewportBorderBounds();
        } else {
            Dimension dim = getSize();
            return new Rectangle(0, 0, dim.width, dim.height);
        }
    }

    /**
     * Overrides the default implementation to test for and configure any {@link
     * JScrollPane} parent.
     */
    public void addNotify() {
        super.addNotify();
        System.out.println("add notify called");
        Container p = getParent();
        if (p instanceof JViewport) {
            Container vp = p.getParent();
            if (vp instanceof JScrollPane) {
                setEnclosingScrollPane((JScrollPane) vp);
            }
        }
    }

    /**
     * Overrides the default implementation unconfigure any {@link JScrollPane}
     * parent.
     */
    public void removeNotify() {
        super.removeNotify();
        setEnclosingScrollPane(null);
    }


    /**
     * Description of the Field
     */
    protected Document doc = null;

    /**
     * The queue to hand painting and layout events
     */
    RenderQueue queue;

    /**
     * Description of the Method
     */
    protected void init() {

        queue = RenderQueue.getInstance();
        documentListeners = new HashMap();
        setBackground(Color.white);
        super.setLayout(null);

        layoutThread = new Thread(new LayoutLoop(this), "FlyingSaucer-Layout");
        renderThread = new Thread(new RenderLoop(this), "FlyingSaucer-Render");

        layoutThread.start();
        renderThread.start();
    }

    public synchronized void shutdown() {
        try {
            layoutThread.interrupt();
            layoutThread.join();

            renderThread.interrupt();
            renderThread.join();
        } catch (InterruptedException e) {
            // ignore
        }
    }

    int rendered_width = 0;

    protected int getRenderWidth() {
        return rendered_width;
    }

    protected void setRenderWidth(int renderWidth) {
        this.rendered_width = renderWidth;
    }


    boolean layoutInProgress = false;


    public ReflowEvent last_event = null;

    protected Context newContext(Graphics2D g) {
        XRLog.layout(Level.FINEST, "new context begin");

        getContext().setCanvas(this);
        getContext().setGraphics(g);

        Rectangle extents;

        if (enclosingScrollPane != null) {
            Rectangle bnds = enclosingScrollPane.getViewportBorderBounds();
            extents = new Rectangle(0, 0, bnds.width, bnds.height);
            //Uu.p("bnds = " + bnds);
        } else {
            extents = new Rectangle(getWidth(), getHeight());//200, 200 ) );

        }


        //Uu.p("newContext() = extents = " + extents);
        getContext().setMaxWidth(0);
        //getContext().setMaxHeight(0);
        XRLog.layout(Level.FINEST, "new context end");
        //Uu.p("new context with extents: " + extents);
        setRenderWidth((int) extents.getWidth());
        return getContext().newContextInstance(extents);
    }

    public void doActualLayout(Graphics g) {
        //Uu.p("doActualLayout called");
        this.removeAll();
        if (g == null) {
            return;
        }
        if (doc == null) {
            return;
        }

// set up CSS
        Context c = newContext((Graphics2D) g);
//getContext().setMaxWidth(0);
        this.layout_context = c;
        getRenderingContext().getTextRenderer().setupGraphics(c.getGraphics());
//TODO: maybe temporary hack
        if (c.getBlockFormattingContext() != null) c.popBFC();//we set one for the top level before
        // do the actual layout
        BlockBoxing.count = 0;
        bh = new org.xhtmlrenderer.layout.BoxHolder();
//Uu.p("doing actual layout here");
        body_box = Boxing.layout(c, new DomToplevelNode(doc), bh);
//Uu.p("body box = " + body_box);
        if (!c.isStylesAllPopped()) {
            XRLog.layout(Level.SEVERE, "mismatch in style popping and pushing");
        }

        XRLog.layout(Level.FINEST, "is a fixed child: " + body_box.isChildrenExceedBounds());

// if there is a fixed child then we need to set opaque to false
// so that the entire viewport will be repainted. this is slower
// but that's the hit you get from using fixed layout
        if (body_box.isChildrenExceedBounds()) {
            super.setOpaque(false);
        } else {
            super.setOpaque(true);
        }

        getRenderingContext().setRootBox(body_box);

        XRLog.layout(Level.FINEST, "after layout: " + body_box);

        intrinsic_size = new Dimension(getContext().getMaxWidth(), body_box.height);
        //Uu.p("intrinsic size = " + intrinsic_size);
        if (intrinsic_size.width != this.getWidth()) {
            //Uu.p("intrisic and this widths don't match: " + this.getSize() + " "  + intrinsic_size);
            this.setPreferredSize(new Dimension(intrinsic_size.width, this.getHeight()));
            //this.setPreferredSize(intrinsic_size);
            this.revalidate();
        }

        // if doc is shorter than viewport
        // then stretch canvas to fill viewport exactly
        // then adjust the body element accordingly
        if (enclosingScrollPane != null) {
            if (intrinsic_size.height < enclosingScrollPane.getViewport().getHeight()) {
                //Uu.p("int height is less than viewport height");
                if (enclosingScrollPane.getViewport().getHeight() != this.getHeight()) {
                    this.setPreferredSize(new Dimension(getWidth(), enclosingScrollPane.getViewport().getHeight()));
                    this.revalidate();
                }
//Uu.p("need to do the body hack");
                if (body_box != null) {
                    body_box.height = enclosingScrollPane.getViewport().getHeight();
                    bodyExpandHack(body_box, body_box.height);
                    intrinsic_size.height = body_box.height;
                }
            } else {  // if doc is taller than viewport
                if (this.getHeight() != intrinsic_size.height) {
                    this.setPreferredSize(new Dimension(getWidth(), intrinsic_size.height));
                    this.revalidate();
                }

            }
        }


        queue.dispatchRepaintEvent(new ReflowEvent(ReflowEvent.LAYOUT_COMPLETE));
        this.fireDocumentLoaded();
    }

    private static void bodyExpandHack(Box root, int view_height) {
        for (int i = 0; i < root.getChildCount(); i++) {
            // set the html box to the max
            Box html = root.getChild(i);
            if (html.element != null && html.element.getNodeName().equals("html")) {
                html.height = view_height;
                // set the body box to the max
                for (int j = 0; j < html.getChildCount(); j++) {
                    Box body = html.getChild(j);
                    if (body.element != null && body.element.getNodeName().equals("body")) {
                        body.height = view_height;
                    }
                }
            }
        }
    }


    /**
     * Description of the Method
     */
    protected void fireDocumentLoaded() {
        Iterator it = this.documentListeners.keySet().iterator();
        while (it.hasNext()) {
            DocumentListener list = (DocumentListener) it.next();
            list.documentLoaded();
        }
    }


    /*
    * ========= UserInterface implementation ===============
    */

    /**
     * Description of the Field
     */
    public Element hovered_element = null;

    /**
     * Description of the Field
     */
    public Element active_element = null;

    /**
     * Description of the Field
     */
    public Element focus_element = null;


    /**
     * Gets the hover attribute of the BasicPanel object
     *
     * @param e PARAM
     * @return The hover value
     */
    public boolean isHover(org.w3c.dom.Element e) {
        if (e == hovered_element) {
            return true;
        }
        return false;
    }

    /**
     * Gets the active attribute of the BasicPanel object
     *
     * @param e PARAM
     * @return The active value
     */
    public boolean isActive(org.w3c.dom.Element e) {
        if (e == active_element) {
            return true;
        }
        return false;
    }

    /**
     * Gets the focus attribute of the BasicPanel object
     *
     * @param e PARAM
     * @return The focus value
     */
    public boolean isFocus(org.w3c.dom.Element e) {
        if (e == focus_element) {
            return true;
        }
        return false;
    }


    /**
     * Description of the Method
     *
     * @param e PARAM
     */
    public void componentHidden(ComponentEvent e) {
    }

    /**
     * Description of the Method
     *
     * @param e PARAM
     */
    public void componentMoved(ComponentEvent e) {
    }

    /**
     * Description of the Method
     *
     * @param e PARAM
     */
    public void componentResized(ComponentEvent e) {
        Uu.p("componentResized() " + this.getSize());
        Uu.p("viewport = " + enclosingScrollPane.getViewport().getSize());
        if (doc != null)
            RenderQueue.getInstance().dispatchLayoutEvent(new ReflowEvent(ReflowEvent.CANVAS_RESIZED,
                    enclosingScrollPane.getViewport().getSize()));
    }

    /**
     * Description of the Method
     *
     * @param e PARAM
     */
    public void componentShown(ComponentEvent e) {
    }

}
