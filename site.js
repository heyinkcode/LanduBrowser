const menuButton = document.querySelector('.menu-toggle');
const navigation = document.querySelector('#navigation');

function setMenuOpen(open) {
  menuButton.setAttribute('aria-expanded', String(open));
  menuButton.setAttribute('aria-label', open ? '关闭导航' : '打开导航');
  navigation.classList.toggle('is-open', open);
}

menuButton.addEventListener('click', () => setMenuOpen(menuButton.getAttribute('aria-expanded') !== 'true'));
navigation.addEventListener('click', event => {
  if (event.target.closest('a')) setMenuOpen(false);
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && menuButton.getAttribute('aria-expanded') === 'true') {
    setMenuOpen(false);
    menuButton.focus();
  }
});
document.addEventListener('click', event => {
  if (!event.target.closest('.site-header')) setMenuOpen(false);
});
matchMedia('(min-width: 801px)').addEventListener('change', event => {
  if (event.matches) setMenuOpen(false);
});

const previewTabs = [...document.querySelectorAll('[role="tab"]')];
function selectPreview(selected) {
  for (const tab of previewTabs) {
    const active = tab === selected;
    tab.setAttribute('aria-selected', String(active));
    tab.tabIndex = active ? 0 : -1;
    document.getElementById(tab.getAttribute('aria-controls')).hidden = !active;
  }
}
for (const tab of previewTabs) {
  tab.addEventListener('click', () => selectPreview(tab));
  tab.addEventListener('keydown', event => {
    const currentIndex = previewTabs.indexOf(tab);
    const nextIndex = { ArrowRight: (currentIndex + 1) % previewTabs.length, ArrowLeft: (currentIndex - 1 + previewTabs.length) % previewTabs.length, Home: 0, End: previewTabs.length - 1 }[event.key];
    if (nextIndex === undefined) return;
    event.preventDefault();
    selectPreview(previewTabs[nextIndex]);
    previewTabs[nextIndex].focus();
  });
}
