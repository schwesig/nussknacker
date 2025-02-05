import React, {ReactNode, useEffect, useState} from "react"
import {useTranslation} from "react-i18next"
import {useSelector} from "react-redux"
import {NavLink} from "react-router-dom"
import {ReactComponent as NussknackerLogo} from "../assets/img/nussknacker-logo.svg"
import {getLoggedUser, getTabs} from "../reducers/selectors/settings"
import {Flex} from "./common/Flex"
import {ButtonWithFocus} from "./withFocus"
import {useSearchQuery} from "../containers/hooks/useSearchQuery"
import {DynamicTabData} from "../containers/DynamicTab"
import {CustomTabBasePath} from "../containers/paths"
import {absoluteBePath} from "../common/UrlUtils";

function useStateWithRevertTimeout<T>(startValue: T, time = 10000): [T, React.Dispatch<React.SetStateAction<T>>] {
  const [defaultValue] = useState<T>(startValue)
  const [value, setValue] = useState<T>(defaultValue)
  useEffect(() => {
    let t
    if (value) {
      t = setTimeout(() => {
        setValue(defaultValue)
      }, time)
    }
    return () => clearTimeout(t)
  }, [value, time])
  return [value, setValue]
}

function TabElement(props: {tab: DynamicTabData}): JSX.Element {
  const {id, type, url, title} = props.tab
  switch (type) {
    case "Local":
      return <NavLink to={url} >{title}</NavLink>
    case "Url":
      return <a href={url} >{title}</a>
    default:
      return <NavLink to={`${CustomTabBasePath}/${id}`} >{title}</NavLink>
  }
}

function Menu({onClick}: { onClick: () => void }): JSX.Element {
  const tabs = useSelector(getTabs)
  const loggedUser = useSelector(getLoggedUser)
  const dynamicTabData = tabs.filter(({requiredPermission}) => !requiredPermission || loggedUser.hasGlobalPermission(requiredPermission))
  return (
    <ul id="menu-items" onClick={onClick}>
      {dynamicTabData.map(tab => (
        <li key={tab.id}>
          <TabElement tab={tab} />
        </li>
      ))}
    </ul>
  )
}

function InstanceLogo() {
  const [validLogo, setValidLogo] = useState(false)
  return (
    <div className={validLogo ? "navbar-instance" : ""}>
      <img src={absoluteBePath("/assets/img/instance-logo.svg")} alt="" style={validLogo ? {display: "flex"} : {display: "none"}}
           className={validLogo ? "navbar-instance-logo" : ""} onLoad={(e) => {
        setValidLogo(true);
      }}/>
    </div>
  );
}

type Props = {
  appPath: string,
  rightElement?: ReactNode,
  leftElement?: ReactNode,
}

const Spacer = () => <Flex flex={1}/>

export function MenuBar({appPath, rightElement = null, leftElement = null}: Props): JSX.Element {
  const [expanded, setExpanded] = useStateWithRevertTimeout(false)
  const {t} = useTranslation()

  /**
   * In some cases (eg. docker demo) we serve Grafana and Kibana from nginx proxy, from root app url, and when service responds with error
   * then React app catches this and shows error page. To make it render only error, without app menu, we have mark iframe
   * requests with special query parameter so that we can recognize them and skip menu rendering.
   */
  const [{iframe: isLoadAsIframe}] = useSearchQuery<{ iframe: boolean }>()
  if (isLoadAsIframe) {
    return null
  }

  return (
    <header>
      <nav id="main-menu" className={`navbar navbar-default ${expanded ? "expanded" : "collapsed"}`}>
        <Flex>
          {leftElement}
          <NavLink className="navbar-brand" to={appPath} title={t("menu.goToMainPage", "Go to main page")}>
            <NussknackerLogo className={"navbar-brand-logo"}/>
          </NavLink>
          <InstanceLogo/>
          {rightElement}
          <Spacer/>
          <ButtonWithFocus className="expand-button" onClick={() => setExpanded(v => !v)}>
            <span className={`glyphicon glyphicon-menu-${expanded ? "up" : "down"}`}/>
          </ButtonWithFocus>
          <Menu onClick={() => setExpanded(false)}/>
        </Flex>
      </nav>
    </header>
  )
}

