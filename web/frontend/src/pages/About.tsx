import { hrefOf } from '../router';

export default function About({ accepted }: { accepted: boolean }) {
  return (
    <article className="about">
      <p className="eyebrow">Sobre o projeto</p>
      <h1>Um mapa do que as redes Wi-Fi já anunciam em público</h1>
      <p className="lede">
        O Wardriver é um dispositivo portátil de baixo custo que escuta, de forma passiva, os anúncios que os pontos de acesso Wi-Fi
        enviam a todos ao redor, e os associa ao lugar onde foram ouvidos. Este site reúne o resultado.
      </p>

      <aside className="callout" aria-labelledby="public-only">
        <h2 id="public-only">Somente informações públicas</h2>
        <div className="two-col">
          <div>
            <h3>O que é coletado</h3>
            <ul>
              <li>Nome da rede (SSID), quando ela o divulga</li>
              <li>Identificador do equipamento (BSSID)</li>
              <li>Canal e intensidade do sinal</li>
              <li>Tipo de criptografia e presença de WPS</li>
              <li>Posição do coletor no momento da captura</li>
            </ul>
          </div>
          <div>
            <h3>O que nunca é coletado</h3>
            <ul>
              <li>Senhas ou chaves de rede</li>
              <li>Conteúdo de comunicação ou tráfego de dados</li>
              <li>Endereços de aparelhos de usuários (o MAC de cliente é descartado dentro do rádio)</li>
              <li>Pedidos de busca de aparelhos (<em>probe requests</em>)</li>
              <li>Nenhuma conexão a redes, nenhum ataque, nenhuma interferência</li>
            </ul>
          </div>
        </div>
      </aside>

      <h2>Como funciona</h2>
      <ol className="steps">
        <li>
          <strong>Coleta.</strong> Um ESP32 percorre os canais 1 a 13 do Wi-Fi e ouve apenas os quadros que os pontos de acesso emitem
          sozinhos (<em>beacons</em> e respostas a sondagem). Cada rede ouvida é combinada com a posição de um módulo GPS ou do celular.
        </li>
        <li>
          <strong>Sincronização.</strong> Um aplicativo Android baixa os registros por Bluetooth e, se configurado, envia as redes a
          este servidor em intervalos regulares.
        </li>
        <li>
          <strong>Consulta.</strong> Este site apenas exibe o que foi enviado: um mapa, uma lista e uma análise, com filtros. Não há
          como enviar, importar ou baixar dados por aqui.
        </li>
      </ol>

      <h2>O que os dados mostram</h2>
      <p>
        Quantas redes usam criptografia fraca ou nenhuma, quais canais estão mais disputados, quantas redes ocultas são na verdade
        interfaces extras de roteadores de operadora, e quais fabricantes dominam cada região. O objetivo é conscientização sobre
        segurança de redes domésticas e corporativas.
      </p>

      <h2>Limites que você deve conhecer</h2>
      <ul>
        <li>A posição de uma rede é a do <em>receptor</em> na captura mais forte, não uma triangulação: o ponto de acesso pode estar a dezenas de metros.</li>
        <li>O coletor escuta cada canal só uma fração do tempo; redes fracas podem ter escapado.</li>
        <li>Só a faixa de 2,4 GHz é coberta; o ESP32 não escuta 5 GHz.</li>
        <li>A cidade é a do centro urbano mais próximo, e o fabricante vem do prefixo do BSSID (registro OUI do IEEE): endereços virtuais ou aleatórios podem ficar sem fabricante.</li>
        <li>O site guarda uma linha por equipamento (a captura mais forte), não o trajeto percorrido.</li>
      </ul>

      <h2>Privacidade e uso responsável</h2>
      <p>
        O nome de uma rede doméstica pode identificar uma pessoa, e a posição pode indicar onde ela mora. Por isso o acesso aos dados
        exige o aceite do <a href={hrefOf('/termo')}>termo de compromisso</a>: uso para estudo, nenhuma tentativa de acesso às redes e
        nenhuma tentativa de identificar pessoas. Este site não usa cookies de rastreamento nem ferramentas de análise; o único cookie é
        o que lembra o aceite do termo até você fechar o navegador. Os blocos do mapa vêm do OpenStreetMap, que enxerga o endereço IP de
        quem consulta e as regiões que olha, mas nunca os dados das redes.
      </p>

      <h2>Autoria</h2>
      <p>
        Projeto de <strong>Fernando Henrique Campanhã Abrão</strong> e <strong>Aline Almeida Vajalegre</strong>. Código em domínio
        público (CC0); componentes de terceiros mantêm suas licenças.
      </p>

      <p className="cta">
        {accepted ? (
          <a className="btn btn-solid" href={hrefOf('/')}>Abrir o painel</a>
        ) : (
          <a className="btn btn-solid" href={hrefOf('/termo')}>Ler o termo e entrar</a>
        )}
      </p>
    </article>
  );
}
