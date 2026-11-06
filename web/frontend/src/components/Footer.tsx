export default function Footer() {
  return (
    <footer className="footer">
      <p>
        Somente informações que os pontos de acesso transmitem em público. Nenhuma rede foi acessada, nenhum tráfego foi capturado.{' '}
        <a href="#/sobre">Saiba mais</a>
      </p>
      <p>
        Mapa © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a>
        {' · '}Fabricantes: registro OUI do IEEE.
      </p>
    </footer>
  );
}
