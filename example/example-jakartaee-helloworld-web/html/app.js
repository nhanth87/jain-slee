(() => {
  const out = document.getElementById("out");
  const btn = document.getElementById("btn-ping");
  const raBase = (window.HELLO_RA_BASE || "http://127.0.0.1:8081").replace(/\/$/, "");

  btn.addEventListener("click", async () => {
    out.textContent = "Requesting…";
    try {
      const res = await fetch(`${raBase}/hello`, { method: "GET" });
      const text = await res.text();
      out.textContent = `${res.status} ${res.statusText}\n\n${text}`;
    } catch (err) {
      out.textContent = String(err);
    }
  });
})();
