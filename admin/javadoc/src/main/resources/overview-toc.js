/*
 * Enhances javadoc TOC sidebars with section headings from description blocks.
 *
 * - Overview page (package-index-page): creates a TOC from scratch since
 *   javadoc doesn't generate one for the overview page.
 * - Package-summary pages: injects h2/h3 headings from the package description
 *   into the existing javadoc-generated TOC after the "Description" entry.
 */
document.addEventListener("DOMContentLoaded", function () {

    // --- Helper: build TOC entries from headings ---
    function buildHeadingEntries(block) {
        var headings = block.querySelectorAll("h2, h3");
        var entries = [];
        headings.forEach(function (h) {
            var id = h.id || h.textContent.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-");
            if (!h.id) h.id = id;

            var li = document.createElement("li");
            var a = document.createElement("a");
            a.href = "#" + id;
            a.tabIndex = 0;
            a.textContent = h.textContent.trim();
            li.appendChild(a);

            if (h.tagName === "H3") {
                li.style.paddingLeft = "1em";
            }

            entries.push(li);
        });
        return entries;
    }

    // --- Package-summary pages: enhance existing TOC ---
    var pkgDesc = document.querySelector("section.package-description");
    if (pkgDesc) {
        var block = pkgDesc.querySelector("div.block");
        var tocList = document.querySelector("nav.toc ol.toc-list");
        if (block && tocList) {
            var entries = buildHeadingEntries(block);
            if (entries.length > 0) {
                // Find the "Description" entry to insert after
                var descItem = tocList.querySelector("li");
                var insertBefore = descItem ? descItem.nextSibling : tocList.firstChild;
                entries.forEach(function (li) {
                    tocList.insertBefore(li, insertBefore);
                });
            }
        }
        return;
    }

    // --- Overview page: create TOC from scratch ---
    if (!document.body.classList.contains("package-index-page")) return;

    var mainGrid = document.querySelector("div.main-grid");
    var mainEl = mainGrid && mainGrid.querySelector("main");
    if (!mainGrid || !mainEl) return;

    var block = mainEl.querySelector("div.block");
    if (!block) return;

    var entries = buildHeadingEntries(block);
    if (entries.length === 0) return;

    // Build the TOC list
    var ol = document.createElement("ol");
    ol.className = "toc-list";
    ol.tabIndex = -1;

    entries.forEach(function (li) {
        ol.appendChild(li);
    });

    // Also add "Packages" entry pointing to the package list
    var pkgTable = mainEl.querySelector("div.summary-table, table.summary-table, div#all-packages-table");
    if (pkgTable) {
        var pkgHeading = pkgTable.previousElementSibling;
        if (pkgHeading && pkgHeading.id) {
            var li = document.createElement("li");
            var a = document.createElement("a");
            a.href = "#" + pkgHeading.id;
            a.tabIndex = 0;
            a.textContent = "Packages";
            li.appendChild(a);
            ol.appendChild(li);
        }
    }

    // Create the nav element matching javadoc's structure
    var nav = document.createElement("nav");
    nav.role = "navigation";
    nav.className = "toc";
    nav.setAttribute("aria-label", "Table of contents");

    var header = document.createElement("div");
    header.className = "toc-header";
    header.textContent = "Contents";
    nav.appendChild(header);
    nav.appendChild(ol);

    // Add hide/show buttons
    var hideBtn = document.createElement("button");
    hideBtn.className = "hide-sidebar";
    hideBtn.innerHTML = '<span>Hide sidebar&nbsp;</span><img src="resource-files/left.svg" alt="Hide sidebar">';
    nav.appendChild(hideBtn);

    var showBtn = document.createElement("button");
    showBtn.className = "show-sidebar";
    showBtn.innerHTML = '<img src="resource-files/right.svg" alt="Show sidebar"><span>&nbsp;Show sidebar</span>';
    nav.appendChild(showBtn);

    hideBtn.addEventListener("click", function () {
        nav.classList.add("hidden");
    });
    showBtn.addEventListener("click", function () {
        nav.classList.remove("hidden");
    });

    mainGrid.insertBefore(nav, mainEl);
});
